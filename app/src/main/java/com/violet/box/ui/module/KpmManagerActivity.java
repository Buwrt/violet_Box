package com.violet.box.ui.module;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.violet.box.R;
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
public class KpmManagerActivity extends Activity {

    private static final int REQ_PICK = 4711;
    private static final String APATCH_PKG = "me.bmax.apatch";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "kpm-mgr"));

    private RecyclerView recycler;
    private ProgressBar loading;
    private TextView empty;
    private TextView envRoot;
    private TextView envApatch;
    private TextView envDir;

    private final List<Object> rows = new ArrayList<>();
    private KpmAdapter adapter;
    /** Cached from the last scan so button guards never block the main thread with "su -c id -u". */
    private volatile boolean rootReady = false;

    // ------------------------------------------------------------------ model

    private static final class Row {
        final KpmInfo info;
        final boolean installed;
        final boolean disabled;

        Row(KpmInfo info, boolean installed, boolean disabled) {
            this.info = info;
            this.installed = installed;
            this.disabled = disabled;
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

        findViewById(R.id.btnKpmBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnKpmRefresh).setOnClickListener(v -> reload());
        findViewById(R.id.btnKpmPick).setOnClickListener(v -> pickFile());

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new KpmAdapter();
        recycler.setAdapter(adapter);

        reload();
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

            final StringBuilder env1 = new StringBuilder();
            env1.append("ROOT：").append(root ? "已获取" : "未获取（无法刷入）");
            final StringBuilder env2 = new StringBuilder();
            env2.append("KernelPatch / APatch：").append(apatch ? ("APatch" + (ver.isEmpty() ? "" : " " + ver)) : "未检测到");
            if (kptools) env2.append("（kptools 可用）");
            final StringBuilder env3 = new StringBuilder();
            env3.append("模块目录：").append(KpmShell.KPMS_DIR)
                    .append(root ? (apatch ? "（就绪）" : "（APatch 不在，刷入后不会被加载）") : "（需 ROOT 才能读取）");

            main.post(() -> {
                envRoot.setText(env1.toString());
                envApatch.setText(env2.toString());
                envDir.setText(env3.toString());

                rows.clear();
                if (!installedRows.isEmpty()) {
                    rows.add("已刷入（" + installedRows.size() + "）");
                    rows.addAll(installedRows);
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
                confirmInstall(finalInfo);
            });
        });
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
            path.setText(info.path == null ? "" : info.path);

            badge.setText("KPM");
            if (row.installed) {
                state.setText(row.disabled ? "已禁用" : "已启用");
                state.setTextColor(getResources().getColor(row.disabled
                        ? R.color.ios_semantic_negative : R.color.explore_emerald_600));
                btnInstall.setVisibility(View.GONE);
                btnToggle.setVisibility(View.VISIBLE);
                btnToggle.setText(row.disabled ? "启用" : "禁用");
                btnUninstall.setVisibility(View.VISIBLE);
            } else {
                state.setText("未刷入");
                state.setTextColor(getResources().getColor(R.color.ios_text_secondary));
                btnInstall.setVisibility(View.VISIBLE);
                btnToggle.setVisibility(View.GONE);
                btnUninstall.setVisibility(View.GONE);
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
}
