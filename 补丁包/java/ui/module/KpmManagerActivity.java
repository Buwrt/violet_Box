package com.violet.box.ui.module;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.violet.box.R;
import com.violet.box.kpm.KpmEmbedTool;
import com.violet.box.kpm.KpmInfo;
import com.violet.box.kpm.KpmShell;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KPM (KernelPatch Module) flash centre.
 *
 * What "刷入" means here, and what it does not:
 *
 * APatch stores an installed KPM as {@code /data/adb/ap/kpm/<id>/<id>.kpm} and loads every
 * non-disabled entry at boot. Installing is therefore just placing a file - which we can do
 * over a root shell, and which is exactly what APatch's own installKpm() does. It takes effect
 * after a reboot.
 *
 * Hot-loading into the running kernel is a different path: it goes through the KernelPatch
 * supercall (syscall 45 / SUPERCALL_KPM_LOAD) and needs the superkey, which only APatch knows.
 * We intentionally do not pretend otherwise - the environment card says so and we offer a
 * shortcut that opens APatch for that step.
 */
public class KpmManagerActivity extends AppCompatActivity {

    private static final int REQ_PICK = 4711;
    private static final String APATCH_PKG = "me.bmax.apatch";

    /** Remembers whether the user folded the 「运行环境」 card away. */
    private static final String PREF_ENV = "kpm_manager";
    private static final String KEY_ENV_EXPANDED = "env_expanded";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "kpm-mgr"));

    private RecyclerView recycler;
    private ProgressBar loading;
    private TextView empty;
    private TextView envRoot;
    private TextView envApatch;
    private TextView envDir;
    private TextView envEmbed;
    private View groupEnvBody;
    private TextView tvEnvSummary;
    private TextView btnEnvToggle;
    private SharedPreferences prefs;
    /** Mirrors the persisted state so the toggle never has to touch disk mid-frame. */
    private boolean envExpanded = true;

    private final List<Object> rows = new ArrayList<>();
    private KpmAdapter adapter;
    /** Cached from the last scan so button guards never block the main thread with "su -c id -u". */
    private volatile boolean rootReady = false;
    /** Set by the last scan: whether the boot image can be re-patched (and why not). */
    private volatile boolean embedReady = false;
    private volatile String embedDevice = "";
    private volatile String embedVersion = "";
    private volatile String embedReason = "";

    // ------------------------------------------------------------------ model

    private static final class Row {
        final KpmInfo info;
        final boolean installed;
        final boolean disabled;
        /** True for KPMs carved out of the boot image (the Embed route, APatch 「已嵌入」). */
        final boolean embedded;
        /** For embedded rows: the block device the KPM was carved from. */
        final String bootDevice;
        /** For embedded rows: app-readable carved .kpm in our cache dir. */
        final String embeddedFile;

        Row(KpmInfo info, boolean installed, boolean disabled) {
            this(info, installed, disabled, false, null, null);
        }

        Row(KpmInfo info, boolean installed, boolean disabled, boolean embedded,
            String bootDevice, String embeddedFile) {
            this.info = info;
            this.installed = installed;
            this.disabled = disabled;
            this.embedded = embedded;
            this.bootDevice = bootDevice;
            this.embeddedFile = embeddedFile;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kpm_manager);

        recycler = findViewById(R.id.rvKpm);
        loading = findViewById(R.id.pbKpm);
        empty = findViewById(R.id.tvKpmEmpty);
        envRoot = findViewById(R.id.tvKpmEnvRoot);
        envApatch = findViewById(R.id.tvKpmEnvApatch);
        envDir = findViewById(R.id.tvKpmEnvDir);
        envEmbed = findViewById(R.id.tvKpmEnvEmbed);
        groupEnvBody = findViewById(R.id.groupKpmEnvBody);
        tvEnvSummary = findViewById(R.id.tvKpmEnvSummary);
        btnEnvToggle = findViewById(R.id.btnKpmEnvToggle);

        // 运行环境：第一次用是展开的；用户收起过一次之后，之后每次进来都是收起的，
        // 点标题行（或右侧「展开」）才重新展开。
        prefs = getSharedPreferences(PREF_ENV, MODE_PRIVATE);
        envExpanded = prefs.getBoolean(KEY_ENV_EXPANDED, true);
        findViewById(R.id.rowKpmEnvToggle).setOnClickListener(v -> {
            envExpanded = !envExpanded;
            prefs.edit().putBoolean(KEY_ENV_EXPANDED, envExpanded).apply();
            applyEnvExpanded();
        });

        // 原生标题栏：返回箭头 + 「刷新」菜单，与「模块备份」页保持同一套 UI
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.btnKpmPick).setOnClickListener(v -> pickFile());

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new KpmAdapter();
        recycler.setAdapter(adapter);

        applyEnvExpanded();
        reload();
    }

    /**
     * Applies the collapsed state of the environment card.
     *
     * When folded, the raw detection lines are hidden but a one-line digest stays visible
     * ({@code ROOT: 已获取 · APatch 115028}), so the user still gets the verdict without
     * having to open the card every single time.
     */
    private void applyEnvExpanded() {
        groupEnvBody.setVisibility(envExpanded ? View.VISIBLE : View.GONE);
        btnEnvToggle.setText(envExpanded ? R.string.kpm_env_collapse : R.string.kpm_env_expand);
        tvEnvSummary.setText(buildEnvSummary());
        tvEnvSummary.setVisibility(envExpanded ? View.GONE : View.VISIBLE);
    }

    /** Short "ROOT · APatch" digest shown in the folded header. Empty until the first scan lands. */
    private String buildEnvSummary() {
        StringBuilder sb = new StringBuilder();
        String root = envRoot == null ? "" : envRoot.getText().toString();
        if (root.startsWith("ROOT：")) {
            sb.append(root.startsWith("ROOT：已获取") ? "ROOT 已获取" : "无 ROOT");
        }
        String ap = envApatch == null ? "" : envApatch.getText().toString();
        int bar = ap.indexOf('：');
        if (bar >= 0) {
            String tailText = ap.substring(bar + 1);
            if (!tailText.startsWith("检测中") && !tailText.startsWith("未检测到")) {
                sb.append(sb.length() > 0 ? " · " : "").append(tailText);
            }
        }
        return sb.toString();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_kpm, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_kpm_refresh) {
            reload();
            return true;
        }
        if (item.getItemId() == R.id.action_kpm_boot) {
            showBootTools();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void busy(boolean b) {
        main.post(() -> {
            loading.setVisibility(b ? View.VISIBLE : View.GONE);
            if (b) empty.setVisibility(View.GONE);
        });
    }

    // ------------------------------------------------------------------ reload

    @SuppressLint("NotifyDataSetChanged")
    private void reload() {
        busy(true);
        io.execute(() -> {
            boolean root = KpmShell.haveRoot();
            rootReady = root;
            boolean apatch = root && KpmShell.isApatch();
            String ver = apatch ? KpmShell.apatchVersion() : "";
            boolean kptools = root && KpmShell.kptoolsAvailable();

            List<Row> installedRows = new ArrayList<>();
            if (root) {
                for (KpmShell.Installed in : KpmShell.listInstalled()) {
                    KpmInfo info = KpmShell.readInfo(in.kpmPath(), in.id);
                    installedRows.add(new Row(info, true, in.disabled));
                }
            }

            List<Row> fileRows = new ArrayList<>();
            for (File f : scanCandidateFiles()) {
                KpmInfo info = KpmInfo.read(f);
                fileRows.add(new Row(info, false, false));
            }

            // Embed 路线：KPM 被合进了 boot/init_boot 镜像，磁盘上没有独立文件。
            //
            // 首选方式和 APatch/FolkPatch 一致：dump 分区 -> kptools unpack -> 读解包出来的
            // kernel。只有这样才能处理「boot 镜像里的 kernel 段被重新压缩」的情况；直接流扫
            // 原始分区只能碰运气。拿不到 kptools 时退化到裸分区扫描作为兜底。
            List<Row> embeddedRows = new ArrayList<>();
            String bootDev = null;
            String kpVersion = "";
            boolean embedOk = false;
            String embedWhy = "";
            if (root) {
                File carveDir = new File(getCacheDir(), "kpm_embedded");
                KpmEmbedTool.Inspect ins;
                try {
                    ins = KpmEmbedTool.inspect(carveDir);
                } catch (Exception e) {
                    ins = new KpmEmbedTool.Inspect();
                    ins.error = String.valueOf(e.getMessage());
                }
                if (ins.ok) {
                    embedOk = true;
                    bootDev = ins.device;
                    kpVersion = ins.versionText();
                    for (KpmInfo info : ins.kpms) {
                        embeddedRows.add(new Row(info, false, false, true, ins.device, info.path));
                    }
                } else {
                    embedWhy = ins.error;
                    KpmShell.BootScan bs = KpmShell.scanEmbeddedBoot(carveDir);
                    if (bs != null) {
                        bootDev = bs.partition;
                        for (KpmInfo info : bs.items) {
                            embeddedRows.add(new Row(info, false, false, true, bs.partition, info.path));
                        }
                    }
                }
            }

            final StringBuilder env1 = new StringBuilder();
            env1.append("ROOT：").append(root ? "已获取" : "未获取（无法刷入）");
            final StringBuilder env2 = new StringBuilder();
            env2.append("KernelPatch / APatch：").append(apatch ? ("APatch" + (ver.isEmpty() ? "" : " " + ver)) : "未检测到");
            if (kptools) env2.append("（kptools 可用）");
            final StringBuilder env3 = new StringBuilder();
            env3.append("模块目录：").append(KpmShell.KPMS_DIR)
                    .append(root ? (apatch ? "（就绪）" : "（APatch 不在，刷入后不会被加载）") : "（需 ROOT 才能读取）");
            if (root) {
                env3.append("\n嵌入检测：");
                if (bootDev != null) {
                    env3.append("在 ").append(bootDev).append(" 中发现 ").append(embeddedRows.size()).append(" 个已嵌入的 KPM");
                } else {
                    env3.append("boot / init_boot 镜像中未发现嵌入的 KPM");
                }
            }
            final String devFinal = bootDev;
            final String verFinal = kpVersion;
            final boolean embedOkFinal = embedOk;
            final String embedWhyFinal = embedWhy;
            embedReady = embedOkFinal;
            embedDevice = embedOkFinal ? devFinal : "";
            embedVersion = verFinal;
            embedReason = embedOkFinal ? "" : embedWhyFinal;

            final StringBuilder env4 = new StringBuilder();
            env4.append("嵌入 boot：");
            if (!root) {
                env4.append("需 ROOT 才能检测");
            } else if (embedOkFinal) {
                env4.append("可用（").append(tail(devFinal)).append(" · KernelPatch ").append(verFinal).append("）");
            } else {
                env4.append("不可用");
                if (!embedWhyFinal.isEmpty()) env4.append(" — ").append(embedWhyFinal);
            }

            main.post(() -> {
                envRoot.setText(env1.toString());
                envApatch.setText(env2.toString());
                envDir.setText(env3.toString());
                envEmbed.setText(env4.toString());
                // 折叠状态下标题行的摘要依赖上面四行，所以结果落地后再刷一次
                applyEnvExpanded();

                rows.clear();
                if (!installedRows.isEmpty()) {
                    rows.add("已刷入（" + installedRows.size() + "）");
                    rows.addAll(installedRows);
                }
                if (!embeddedRows.isEmpty()) {
                    rows.add("已嵌入 · boot 镜像（" + embeddedRows.size() + "）");
                    rows.addAll(embeddedRows);
                }
                if (!fileRows.isEmpty()) {
                    rows.add("可刷入的文件（" + fileRows.size() + "）");
                    rows.addAll(fileRows);
                }
                adapter.notifyDataSetChanged();
                busy(false);
                empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** KPM files we can reach without storage permission, plus the public Download dir when
     *  it happens to be readable. Anything else goes through the file picker. */
    private List<File> scanCandidateFiles() {
        List<File> out = new ArrayList<>();
        File[] dirs = new File[]{
                new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "VioletBox"),
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                new File("/sdcard/Download"),
        };
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".kpm") && !contains(out, f)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static String tail(String path) {
        if (path == null) return "-";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static boolean contains(List<File> list, File f) {
        for (File e : list) {
            if (e.getAbsolutePath().equals(f.getAbsolutePath())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ actions

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(Intent.createChooser(i, "选择 .kpm 文件"), REQ_PICK);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        busy(true);
        final Uri uri = data.getData();
        io.execute(() -> {
            File tmp = new File(getCacheDir(), "kpm_pick.kpm");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new java.io.IOException("openInputStream 返回 null");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            } catch (Exception e) {
                busy(false);
                toast("读取文件失败：" + e.getMessage());
                return;
            }
            KpmInfo info = KpmInfo.read(tmp);
            // The installer re-reads the file from its source path, so keep this copy around
            // (the cache dir is cleaned up by the system/OS eventually).
            File staged = new File(getCacheDir(), "kpm_staged_" + System.currentTimeMillis() + ".kpm");
            File src = tmp.renameTo(staged) ? staged : tmp;
            final KpmInfo finalInfo = new KpmInfo(src.getAbsolutePath(), info.name, info.version,
                    info.license, info.author, info.description, src.length(), info.parsed);
            main.post(() -> {
                busy(false);
                confirmDest(finalInfo);
            });
        });
    }

    // --------------------------------------------------------- 选择安装方式

    /**
     * Two legitimate ways exist to get a KPM running, and they behave very differently:
     *
     * <ul>
     *   <li><b>安装</b> - a plain file under /data/adb/ap/kpm, loaded by APatch at boot, trivially
     *       removable. This is the default.</li>
     *   <li><b>嵌入</b> - written into the boot image itself, loaded before /init even starts, which
     *       is what APatch shows as 「已嵌入」. Removing it means re-patching the boot image again.</li>
     * </ul>
     */
    private void confirmDest(final KpmInfo info) {
        final String[] labels = {
                "安装到模块目录（推荐，可随时卸载）",
                "嵌入到 boot 镜像（随内核最早启动）",
        };
        final int[] choice = {0};
        ArrayAdapter<String> ladapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_single_choice, labels) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                boolean enabled = isEnabled(position);
                v.setEnabled(enabled);
                TextView tv = v.findViewById(android.R.id.text1);
                if (tv != null) {
                    tv.setEnabled(enabled);
                    tv.setAlpha(enabled ? 1f : 0.38f);
                }
                return v;
            }

            @Override
            public boolean isEnabled(int position) {
                return position != 1 || embedReady;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle("刷入方式：" + info.displayName())
                .setSingleChoiceItems(ladapter, 0, (d, which) -> choice[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (d, w) -> {
                    if (choice[0] == 1) confirmEmbed(info);
                    else confirmInstall(info);
                })
                .show();
    }

    // ------------------------------------------------------------- 嵌入流程

    private void confirmEmbed(final KpmInfo info) {
        if (!ensureRoot()) return;
        if (!embedReady) {
            new AlertDialog.Builder(this)
                    .setTitle("无法嵌入")
                    .setMessage("原因：" + embedReason
                            + "\n\n嵌入需要 boot / init_boot 已经被 KernelPatch 打过补丁，"
                            + "并且系统里要有 /data/adb/ap/bin/kptools（APatch 自带）。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("模块：").append(info.displayName()).append('\n');
        msg.append("目标分区：").append(embedDevice).append('\n');
        msg.append("KernelPatch：").append(embedVersion).append('\n');
        msg.append("方式：kptools 重新打包 boot 镜像\n\n");
        msg.append("会自动做的事：\n");
        msg.append("· 先把原 boot 镜像备份到 /sdcard/Download/VioletBox/\n");
        msg.append("· 提取当前 boot 里的 kpimg，避免版本不匹配\n");
        msg.append("· 原样保留已嵌入的其它项目和 ROOT 授权信息\n");
        msg.append("· 重新打包后会再解包校验一遍，通过才允许刷入\n\n");
        msg.append("风险提示：写入 boot 分区一旦失败会无法开机。请选择「仅生成」先拿到镜像，"
                + "或确保已能用 fastboot 线刷。" + "操作完成后需要重启生效。");

        new AlertDialog.Builder(this)
                .setTitle("嵌入到 boot 镜像")
                .setMessage(msg.toString())
                .setNeutralButton("仅生成", (d, w) -> runEmbed(info, false, false))
                .setNegativeButton("取消", null)
                .setPositiveButton("生成并刷入", (d, w) -> runEmbed(info, true, true))
                .show();
    }

    private void runEmbed(final KpmInfo info, boolean flash, boolean bothSlots) {
        busy(true);
        io.execute(() -> {
            KpmEmbedTool.Inspect ins = KpmEmbedTool.inspect(new File(getCacheDir(), "kpm_embedded"),
                    embedDevice);
            if (!ins.ok) {
                finishEmbed(null, ins.error + "\n" + ins.log);
                return;
            }
            List<File> add = new ArrayList<>();
            add.add(new File(info.path));
            KpmEmbedTool.Result r = KpmEmbedTool.patch(ins, add, new ArrayList<String>(),
                    flash, bothSlots);
            finishEmbed(r, ins.log.toString());
        });
    }

    /** Removes one embedded item by re-patching without it. */
    private void confirmRemoveEmbedded(final Row row) {
        if (!ensureRoot()) return;
        String name = row.info.displayName();
        new AlertDialog.Builder(this)
                .setTitle("移除嵌入模块")
                .setMessage("将重新打包 boot 镜像，并从嵌入列表里去掉：\n\n" + name + "\n\n"
                        + "原镜像会先备份到 /sdcard/Download/VioletBox/。\n"
                        + "写入 boot 分区有无法开机的风险，失败时可用该备份恢复。")
                .setNeutralButton("仅生成", (d, w) -> runRemove(row, false, false))
                .setNegativeButton("取消", null)
                .setPositiveButton("移除并刷入", (d, w) -> runRemove(row, true, true))
                .show();
    }

    private void runRemove(final Row row, boolean flash, boolean bothSlots) {
        busy(true);
        io.execute(() -> {
            KpmEmbedTool.Inspect ins = KpmEmbedTool.inspect(new File(getCacheDir(), "kpm_embedded"),
                    embedDevice);
            if (!ins.ok) {
                finishEmbed(null, ins.error + "\n" + ins.log);
                return;
            }
            List<String> remove = new ArrayList<>();
            remove.add(row.info.displayName());
            if (ins.extras != null) {
                // The embedded name may differ from our display label; match against kptools' list.
                for (KpmEmbedTool.Extra e : ins.extras) {
                    if (e.name.equals(row.info.name) || e.name.equals(row.info.displayName())) {
                        remove.clear();
                        remove.add(e.name);
                        break;
                    }
                }
            }
            KpmEmbedTool.Result r = KpmEmbedTool.patch(ins, new ArrayList<File>(), remove,
                    flash, bothSlots);
            finishEmbed(r, ins.log.toString());
        });
    }

    private void finishEmbed(final KpmEmbedTool.Result res, final String extraLog) {
        main.post(() -> {
            busy(false);
            StringBuilder body = new StringBuilder();
            if (res == null) {
                body.append("操作未完成。\n\n");
            } else if (res.ok) {
                body.append("成功。\n\n");
                if (!res.newImage.isEmpty()) body.append("新镜像：").append(res.newImage).append('\n');
                if (!res.backup.isEmpty()) body.append("原镜像备份：").append(res.backup).append('\n');
                if (!res.flashedTo.isEmpty()) {
                    body.append("已刷入：").append(String.join("、", res.flashedTo)).append('\n');
                    body.append("\n请重启设备生效。\n");
                } else {
                    body.append("\n没有写入分区，镜像已生成，可用 fastboot 手动刷入：\n")
                            .append("fastboot flash boot new-boot.img\n");
                }
            } else {
                body.append("失败：").append(res.error).append("\n\n");
                if (!res.backup.isEmpty()) body.append("原镜像备份：").append(res.backup).append('\n');
                if (!res.newImage.isEmpty()) body.append("生成的新镜像：").append(res.newImage).append('\n');
                body.append("\n没有写入 boot 分区。\n");
            }
            String log = res == null ? extraLog : (extraLog + "\n" + res.log);
            AlertDialog.Builder b = new AlertDialog.Builder(KpmManagerActivity.this)
                    .setTitle(res != null && res.ok ? "嵌入完成" : "嵌入未成功")
                    .setMessage(body.toString())
                    .setPositiveButton("知道了", (d, w) -> reload())
                    .setNeutralButton("查看日志", (d, w) -> showLog(log));
            b.show();
        });
    }

    private void showLog(String log) {
        String text = log == null ? "(空)" : (log.length() > 8000 ? log.substring(log.length() - 8000) : log);
        new AlertDialog.Builder(this)
                .setTitle("kptools 日志")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    // -------------------------------------------------- boot 镜像备份 / 恢复

    private void showBootTools() {
        if (!ensureRoot()) return;
        busy(true);
        io.execute(() -> {
            final List<String> files = KpmEmbedTool.backups();
            main.post(() -> {
                busy(false);
                List<String> items = new ArrayList<>();
                items.add("立即备份当前 boot 镜像");
                for (String f : files) {
                    int i = f.lastIndexOf('/');
                    items.add("恢复：" + (i < 0 ? f : f.substring(i + 1)));
                }
                if (items.size() == 1) items.add("（暂无备份，备份会出现在 /sdcard/Download/VioletBox/）");
                final int[] idx = {0};
                new AlertDialog.Builder(KpmManagerActivity.this)
                        .setTitle("boot 镜像备份 / 恢复")
                        .setSingleChoiceItems(items.toArray(new String[0]), 0,
                                (d, which) -> idx[0] = which)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确定", (d, w) -> {
                            if (idx[0] == 0) doBackupBoot();
                            else if (idx[0] - 1 < files.size()) confirmRestore(files.get(idx[0] - 1));
                        })
                        .show();
            });
        });
    }

    private void doBackupBoot() {
        busy(true);
        io.execute(() -> {
            String out = KpmEmbedTool.backupBoot(embedDevice);
            main.post(() -> {
                busy(false);
                toast(out == null ? "备份失败" : "已备份：" + out);
            });
        });
    }

    private void confirmRestore(final String image) {
        String dev = embedDevice;
        if (dev.isEmpty()) {
            List<String> devs = KpmShell.candidateBootDevices();
            if (devs.isEmpty()) {
                toast("找不到 boot 分区");
                return;
            }
            dev = devs.get(0);
        }
        final String target = dev;
        new AlertDialog.Builder(this)
                .setTitle("恢复 boot 镜像")
                .setMessage("将把\n" + image + "\n写回\n" + target + "\n\n"
                        + "这会覆盖当前 boot 分区上所有 KernelPatch 补丁与嵌入模块。确定继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("写入", (d, w) -> {
                    busy(true);
                    io.execute(() -> {
                        String err = KpmEmbedTool.restore(image, target);
                        main.post(() -> {
                            busy(false);
                            toast(err == null ? "已恢复，请重启" : err);
                            reload();
                        });
                    });
                })
                .show();
    }


    private void confirmInstall(KpmInfo info) {
        String name = info.displayName();
        StringBuilder msg = new StringBuilder();
        msg.append("模块：").append(name).append('\n');
        if (info.version != null && !info.version.isEmpty()) msg.append("版本：").append(info.version).append('\n');
        if (info.author != null && !info.author.isEmpty()) msg.append("作者：").append(info.author).append('\n');
        if (info.description != null && !info.description.isEmpty()) msg.append("说明：").append(info.description).append('\n');
        msg.append("\n目录：/data/adb/ap/kpm/").append(info.id()).append("/\n");
        msg.append("刷入后需要重启才会被加载。");
        if (!info.parsed) {
            msg.append("\n\n注意：未能从文件中解析出 .kpm.info，模块 id 将取自文件名，请确认这是有效的 KPM。");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("刷入 KPM")
                .setMessage(msg.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("刷入", (d, w) -> doInstall(info));
        b.show();
    }

    private void doInstall(KpmInfo info) {
        busy(true);
        io.execute(() -> {
            String err = KpmShell.install(info.path, info.id());
            main.post(() -> {
                busy(false);
                if (err != null) {
                    toast(err);
                    return;
                }
                AlertDialog.Builder b = new AlertDialog.Builder(KpmManagerActivity.this)
                        .setTitle("刷入完成")
                        .setMessage("已放入 /data/adb/ap/kpm/" + info.id() + "/\n\n重启后由 APatch 加载。")
                        .setPositiveButton("知道了", null);
                Intent launch = getPackageManager().getLaunchIntentForPackage(APATCH_PKG);
                if (launch != null) {
                    b.setNeutralButton("打开 APatch", (d, w) -> {
                        try {
                            startActivity(launch);
                        } catch (Exception ignored) {
                        }
                    });
                }
                b.show();
                reload();
            });
        });
    }

    private void doToggle(Row row) {
        busy(true);
        io.execute(() -> {
            String err = KpmShell.setEnabled(row.info.id(), row.disabled);
            main.post(() -> {
                busy(false);
                if (err != null) toast(err);
                else toast(row.disabled ? "已启用，重启后生效" : "已禁用，重启后生效");
                reload();
            });
        });
    }

    private void confirmUninstall(Row row) {
        new AlertDialog.Builder(this)
                .setTitle("卸载 KPM")
                .setMessage("将删除 /data/adb/ap/kpm/" + row.info.id() + "/\n\n这个操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> doUninstall(row))
                .show();
    }

    private void doUninstall(Row row) {
        busy(true);
        io.execute(() -> {
            String err = KpmShell.uninstall(row.info.id());
            main.post(() -> {
                busy(false);
                if (err != null) toast(err);
                else toast("已卸载，重启后生效");
                reload();
            });
        });
    }

    // ------------------------------------------------------- 删除待刷入文件

    /**
     * Deletes a loose .kpm that is sitting on disk waiting to be flashed.
     *
     * We only ever touch files we scanned ourselves (the app's own Download dir, the public
     * Download dir), and the confirmation spells out the exact path, because this is a real
     * file deletion with no recycle bin behind it.
     */
    private void confirmDeleteFile(Row row) {
        final String target = row.info.path;
        if (target == null) {
            toast("删除失败：文件路径为空");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage("将从磁盘删除：\n\n" + target
                        + "\n\n只删除这个 .kpm 文件，已经刷进 /data/adb/ap/kpm/ 的模块不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> doDeleteFile(row))
                .show();
    }

    private void doDeleteFile(Row row) {
        busy(true);
        io.execute(() -> {
            String err = deleteQuietly(new File(row.info.path));
            main.post(() -> {
                busy(false);
                toast(err == null ? "已删除文件" : err);
                reload();
            });
        });
    }

    /** @return null on success, otherwise a user-facing reason. */
    private static String deleteQuietly(File f) {
        if (!f.exists()) return "文件已经不在了";
        // App-private dirs delete directly; anything else may need a root shell.
        if (f.delete()) return null;
        String err = KpmShell.deleteFile(f.getAbsolutePath());
        if (err != null) return "删除失败：" + err;
        return f.exists() ? "删除失败：文件仍存在" : null;
    }

    // ------------------------------------------------------------------ adapter

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;

    private final class KpmAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderHolder(inf.inflate(R.layout.item_kpm_header, parent, false));
            }
            return new RowHolder(inf.inflate(R.layout.item_kpm, parent, false));
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? TYPE_HEADER : TYPE_ROW;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).text.setText(String.valueOf(rows.get(position)));
            } else {
                ((RowHolder) holder).bind((Row) rows.get(position));
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.tvKpmHeader);
        }
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final TextView badge;
        final TextView name;
        final TextView state;
        final TextView desc;
        final TextView meta;
        final TextView path;
        final TextView btnInstall;
        final TextView btnToggle;
        final TextView btnUninstall;
        final TextView btnDeleteFile;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            badge = itemView.findViewById(R.id.tvKpmBadge);
            name = itemView.findViewById(R.id.tvKpmName);
            state = itemView.findViewById(R.id.tvKpmState);
            desc = itemView.findViewById(R.id.tvKpmDesc);
            meta = itemView.findViewById(R.id.tvKpmMeta);
            path = itemView.findViewById(R.id.tvKpmPath);
            btnInstall = itemView.findViewById(R.id.btnKpmInstall);
            btnToggle = itemView.findViewById(R.id.btnKpmToggle);
            btnUninstall = itemView.findViewById(R.id.btnKpmUninstall);
            btnDeleteFile = itemView.findViewById(R.id.btnKpmDeleteFile);
        }

        void bind(final Row row) {
            KpmInfo info = row.info;
            name.setText(info.displayName());
            desc.setText(info.description != null && !info.description.isEmpty()
                    ? info.description : (info.parsed ? "该模块未提供说明" : "未能解析 .kpm.info（可能不是 KPM）"));

            StringBuilder m = new StringBuilder();
            String author = info.author == null || info.author.isEmpty() ? "未知作者" : info.author;
            m.append(author);
            if (info.version != null && !info.version.isEmpty()) m.append(" | v").append(info.version);
            if (info.license != null && !info.license.isEmpty()) m.append(" | ").append(info.license);
            meta.setText(m.toString());
            path.setText(row.embedded
                    ? "嵌入于 " + row.bootDevice
                    : (info.path == null ? "" : info.path));

            badge.setText("KPM");
            // 删除按钮默认收起，只有「可刷入的文件」这一类才放出来
            btnDeleteFile.setVisibility(View.GONE);
            if (row.embedded) {
                // 已嵌入：随内核启动，不能用文件开关启停/卸载；只能导出、或重新打包镜像移除
                state.setText("已嵌入");
                state.setTextColor(getResources().getColor(R.color.explore_emerald_600));
                btnInstall.setVisibility(View.VISIBLE);
                btnInstall.setText("导出");
                btnToggle.setVisibility(View.GONE);
                btnUninstall.setVisibility(View.VISIBLE);
                btnUninstall.setText("移除");
                btnInstall.setOnClickListener(v -> {
                    if (!ensureRoot()) return;
                    exportEmbedded(row);
                });
                btnUninstall.setOnClickListener(v -> {
                    if (!ensureRoot()) return;
                    confirmRemoveEmbedded(row);
                });
                return;
            }
            btnInstall.setText("刷入");
            if (row.installed) {
                state.setText(row.disabled ? "已禁用" : "已启用");
                state.setTextColor(getResources().getColor(row.disabled
                        ? R.color.ios_semantic_negative : R.color.explore_emerald_600));
                btnInstall.setVisibility(View.GONE);
                btnToggle.setVisibility(View.VISIBLE);
                btnToggle.setText(row.disabled ? "启用" : "禁用");
                btnUninstall.setVisibility(View.VISIBLE);
            } else {
                // 磁盘上待刷入的 .kpm 文件：给一个删除按钮，免得用户在 app 里刷完想清理
                // 还得跑去找文件管理器
                boolean deletable = row.info.path != null && row.info.path.toLowerCase().endsWith(".kpm");
                state.setText("未刷入");
                state.setTextColor(getResources().getColor(R.color.ios_text_secondary));
                btnInstall.setVisibility(View.VISIBLE);
                btnToggle.setVisibility(View.GONE);
                btnUninstall.setVisibility(View.GONE);
                btnDeleteFile.setVisibility(deletable ? View.VISIBLE : View.GONE);
                btnDeleteFile.setOnClickListener(v -> confirmDeleteFile(row));
            }

            btnInstall.setOnClickListener(v -> {
                if (!ensureRoot()) return;
                confirmInstall(info);
            });
            btnToggle.setOnClickListener(v -> {
                if (!ensureRoot()) return;
                doToggle(row);
            });
            btnUninstall.setOnClickListener(v -> {
                if (!ensureRoot()) return;
                confirmUninstall(row);
            });
        }
    }

    /** Guard for the row buttons. Uses the value cached by the last scan - calling "su" here
     *  would block the main thread for hundreds of milliseconds on every tap. */
    private boolean ensureRoot() {
        if (!rootReady) {
            toast("需要 ROOT 权限");
            return false;
        }
        return true;
    }

    /** Copies a carved embedded .kpm to the app's Download dir so the user can keep it. */
    private void exportEmbedded(Row row) {
        if (row.embeddedFile == null) {
            toast("导出失败：找不到抠出的 KPM 文件");
            return;
        }
        busy(true);
        io.execute(() -> {
            try {
                File src = new File(row.embeddedFile);
                File dir = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "VioletBox");
                if (!dir.isDirectory()) dir.mkdirs();
                String base = KpmInfo.safeId(row.info.displayName());
                File dst = new File(dir, base + ".kpm");
                try (java.io.InputStream in = new java.io.FileInputStream(src);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                }
                main.post(() -> {
                    busy(false);
                    toast("已导出：" + dst.getAbsolutePath());
                });
            } catch (Exception e) {
                main.post(() -> {
                    busy(false);
                    toast("导出失败：" + e.getMessage());
                });
            }
        });
    }
}
