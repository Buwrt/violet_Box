package com.violet.box.ui.repo;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.ChipGroup;
import com.violet.box.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Module download centre.
 *
 * Two ways to grab a module:
 *   1. Tap the row          -> pick an explicit version (radio list, newest first)
 *   2. Tap the "下载最新" button -> straight to whatever GitHub reports as latest
 * Either way, when the user does not actively pick a version we fall back to index 0,
 * which is the newest release. That is the "no choice = newest" rule.
 *
 * Everything is plain Java + OkHttp (already a dependency of PayloadCore) + org.json from the
 * framework, so this adds no Gradle dependencies at all.
 *
 * Threading rule (do not break it): every Toast/dialog must go through <code>toast()</code> or
 * <code>main.post()</code>. Calling Toast directly from a worker thread throws
 * "Can't create handler inside thread ... that has not called Looper.prepare()" and hides the
 * real error behind a useless message.
 */
public class ModuleRepoActivity extends AppCompatActivity {

    /** Point this at a raw file in your own repo to update the catalog without releasing an APK. */
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/Smart-Paocai/violet_Box/main/app/src/main/assets/module_repo.json";

    /** Mirrors of CATALOG_URL, tried in order when the primary host is unreachable. */
    private static final String[] CATALOG_MIRRORS = {
            "https://gh-proxy.com/https://raw.githubusercontent.com/Smart-Paocai/violet_Box/main/app/src/main/assets/module_repo.json",
            "https://raw.gitmirror.com/Smart-Paocai/violet_Box/main/app/src/main/assets/module_repo.json",
    };
    private static final String PREF = "module_repo";
    private static final String KEY_CATALOG_CACHE = "catalog_json";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "module-repo"));
    private final RepoClient repoClient = new RepoClient();

    private RecyclerView recycler;
    private SwipeRefreshLayout swipe;
    private ProgressBar loading;
    private TextView empty;
    private android.widget.EditText search;
    private android.widget.ImageButton clearSearch;
    private TextView count;

    private final List<ModuleEntry> allModules = new ArrayList<>();
    private List<ModuleEntry> visible = new ArrayList<>();
    private ModuleRepoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_module_repo);

        recycler = findViewById(R.id.rvRepoModules);
        swipe = findViewById(R.id.swipeRepo);
        loading = findViewById(R.id.pbRepoLoading);
        empty = findViewById(R.id.tvRepoEmpty);
        search = findViewById(R.id.etRepoSearch);
        clearSearch = findViewById(R.id.btnRepoClearSearch);
        count = findViewById(R.id.tvRepoCount);

        // 原生标题栏：返回箭头 + 「刷新清单」菜单，与「模块备份」页保持同一套 UI
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ModuleRepoAdapter(visible, new ModuleRepoAdapter.Listener() {
            @Override
            public void onPickVersion(ModuleEntry entry) {
                openVersionPicker(entry);
            }

            @Override
            public void onDownloadLatest(ModuleEntry entry) {
                downloadLatest(entry);
            }
        });
        recycler.setAdapter(adapter);

        ((ChipGroup) findViewById(R.id.chipGroupFilter))
                .setOnCheckedChangeListener((group, checkedId) -> applyFilter());

        // Live search: every keystroke re-filters the in-memory catalog, no network involved.
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                clearSearch.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
                applyFilter();
            }
        });
        clearSearch.setOnClickListener(v -> {
            search.setText("");
            search.requestFocus();
        });

        swipe.setOnRefreshListener(this::fetchRemoteCatalog);

        loadLocalCatalog();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_repo, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_repo_refresh) {
            fetchRemoteCatalog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private static File downloadDir(Context context) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "VioletBox");
    }

    /**
     * Toast from any thread. The worker pool has no Looper, so posting is mandatory - doing this
     * on the caller thread is exactly what produced "Can't create handler inside thread".
     */
    private void toast(String text, int duration) {
        if (isFinishing() || isDestroyed()) return;
        main.post(() -> Toast.makeText(getApplicationContext(), text, duration).show());
    }

    private void toast(String text) {
        toast(text, Toast.LENGTH_LONG);
    }

    /** Turn any throwable into something a user can act on instead of "null" or a stack class name. */
    private static String describe(Throwable e) {
        if (e == null) return "未知错误";
        if (e instanceof RepoClient.RepoException) {
            String m = e.getMessage();
            return TextUtils.isEmpty(m) ? "仓库请求失败" : m;
        }
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        // Guard against this class of bug reappearing: a UI object built off the main thread.
        if (msg != null && msg.contains("Can't create handler inside thread")) {
            return "内部错误：界面在非主线程创建（请反馈此问题）";
        }
        if (TextUtils.isEmpty(msg)) {
            if (e instanceof java.net.UnknownHostException) return "网络不可达（DNS 解析失败），请检查网络或代理";
            if (e instanceof java.net.SocketTimeoutException) return "连接超时，GitHub 可能被网络环境阻断";
            if (e instanceof javax.net.ssl.SSLException) return "TLS 握手失败，连接被中断（可能需要代理）";
            return name;
        }
        return name + "：" + msg;
    }

    // ---------------------------------------------------------------- catalog

    private void loadLocalCatalog() {
        setBusy(true);
        io.execute(() -> {
            List<ModuleEntry> parsed = null;
            String err = null;
            try {
                parsed = readCatalogFromAssets();
            } catch (Exception e) {
                err = "内置清单解析失败：" + e.getMessage();
            }
            List<ModuleEntry> result = parsed;
            String error = err;
            main.post(() -> {
                setBusy(false);
                if (result == null) {
                    toast(error);
                    return;
                }
                allModules.clear();
                allModules.addAll(result);
                applyFilter();
            });
        });
    }

    private List<ModuleEntry> readCatalogFromAssets() throws Exception {
        try (InputStream is = getAssets().open("module_repo.json")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            JSONArray array = new JSONObject(json).optJSONArray("modules");
            return array == null ? new ArrayList<>() : ModuleEntry.parseList(array);
        }
    }

    private static List<String> catalogUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(CATALOG_URL);
        urls.addAll(java.util.Arrays.asList(CATALOG_MIRRORS));
        return urls;
    }

    private static String fetchCatalog(String url) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(
                new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new Exception("空响应");
            return body.string();
        }
    }


    /** Pull a newer catalog from GitHub, falling back to mirrors; keeps local assets if all fail. */
    private void fetchRemoteCatalog() {
        swipe.setRefreshing(true);
        io.execute(() -> {
            String json = null;
            String lastError = "未知错误";
            for (String url : catalogUrls()) {
                try {
                    json = fetchCatalog(url);
                    break;
                } catch (Exception e) {
                    lastError = describe(e);
                }
            }
            if (json == null) {
                final String reason = lastError;
                main.post(() -> {
                    swipe.setRefreshing(false);
                    toast("清单更新失败，使用内置清单（" + reason + "）", Toast.LENGTH_SHORT);
                });
                return;
            }
            final String payload = json;
            try {
                JSONArray array = new JSONObject(payload).optJSONArray("modules");
                if (array == null || array.length() == 0) throw new Exception("清单为空");
                List<ModuleEntry> result = ModuleEntry.parseList(array);
                getSharedPreferences(PREF, MODE_PRIVATE)
                        .edit().putString(KEY_CATALOG_CACHE, payload).apply();
                main.post(() -> {
                    swipe.setRefreshing(false);
                    allModules.clear();
                    allModules.addAll(result);
                    applyFilter();
                    toast("清单已更新：" + result.size() + " 个模块", Toast.LENGTH_SHORT);
                });
            } catch (Exception e) {
                main.post(() -> {
                    swipe.setRefreshing(false);
                    toast("清单解析失败，使用内置清单（" + describe(e) + "）", Toast.LENGTH_SHORT);
                });
            }
        });
    }

    private void applyFilter() {
        int checkedId = ((ChipGroup) findViewById(R.id.chipGroupFilter)).getCheckedChipId();
        String[] keys = queryKeys();
        visible = new ArrayList<>();
        for (ModuleEntry entry : allModules) {
            if (checkedId == R.id.chipZip && entry.isKpm()) continue;
            if (checkedId == R.id.chipKpm && !entry.isKpm()) continue;
            if (!matches(entry, keys)) continue;
            visible.add(entry);
        }
        adapter.update(visible);
        boolean none = visible.isEmpty() && !loading.isShown();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (count != null) {
            count.setText(keys.length == 0
                    ? "共 " + allModules.size() + " 个模块"
                    : "匹配 " + visible.size() + " / " + allModules.size() + " 个模块");
        }
    }

    /** Splits the search box into lower-case keywords; all of them must match (AND). */
    private String[] queryKeys() {
        if (search == null) return new String[0];
        CharSequence raw = search.getText();
        if (raw == null) return new String[0];
        String q = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (q.length() == 0) return new String[0];
        return q.split("\\s+");
    }

    /**
     * One haystack per entry: name + author + owner + repo + group + desc + id.
     * Searching the repo slug matters because most users remember "j-hc/zygisk-detach"
     * rather than the display name.
     */
    private static boolean matches(ModuleEntry entry, String[] keys) {
        if (keys.length == 0) return true;
        String hay = (entry.name + " " + entry.author + " " + entry.owner + " " + entry.repo
                + " " + entry.group + " " + entry.desc + " " + entry.id).toLowerCase(Locale.ROOT);
        for (String k : keys) {
            if (!hay.contains(k)) return false;
        }
        return true;
    }

    private void setBusy(boolean busy) {
        loading.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) empty.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------- version UI

    /** Row tap: let the user choose a tag. Should they press download without choosing anything,
     *  the default selection is index 0 == newest release. */
    private void openVersionPicker(ModuleEntry entry) {
        io.execute(() -> {
            try {
                List<RepoClient.ReleaseInfo> releases = repoClient.listReleases(entry);
                main.post(() -> showVersionDialog(entry, releases));
            } catch (Exception e) {
                toast("版本列表获取失败：" + describe(e));
            }
        });
    }

    private void downloadLatest(ModuleEntry entry) {
        startDownload(entry, -1, null);
    }

    private void showVersionDialog(ModuleEntry entry, List<RepoClient.ReleaseInfo> releases) {
        if (releases.isEmpty()) {
            // This runs on the main thread today, but never call Toast.makeText directly here:
            // the previous build grew this exact call from a background caller and hard-crashed
            // with "Can't create handler inside thread".
            toast("没有可用版本", Toast.LENGTH_SHORT);
            return;
        }
        String[] labels = new String[releases.size()];
        for (int i = 0; i < releases.size(); i++) {
            labels[i] = (i == 0 ? "★ 最新版（默认）  " : "") + releases.get(i).toString();
        }
        final int[] selected = {0};

        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setSingleChoiceItems(labels, 0, (dialog, which) -> selected[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("下载此版本", (dialog, which) -> startDownload(entry, selected[0], releases))
                .show();
    }

    // -------------------------------------------------------------- download

    private void startDownload(ModuleEntry entry, int pickedIndex,
                               List<RepoClient.ReleaseInfo> known) {
        io.execute(() -> {
            RepoClient.ReleaseInfo target;
            try {
                if (known != null && !known.isEmpty()) {
                    int idx = pickedIndex < 0 ? 0 : pickedIndex;
                    if (idx >= known.size()) idx = 0;
                    target = known.get(idx);
                } else {
                    // No list resolves yet: fall back to index 0 which is the newest release.
                    List<RepoClient.ReleaseInfo> releases = repoClient.listReleases(entry);
                    target = releases.get(0);
                }
                if (TextUtils.isEmpty(target.downloadUrl)) {
                    throw new Exception("下载地址为空");
                }
                // Resolving the release is I/O, building the dialog is not. The progress dialog
                // must be created on the main thread: a Dialog attaches a Handler, so creating it
                // from the pool thread throws "Can't create handler inside thread ...". That was
                // the real cause of "无法开始下载" - the download never even started.
                main.post(() -> runDownloadOnMainThread(entry, target));
            } catch (Exception e) {
                toast("无法开始下载：" + describe(e));
            }
        });
    }

    private void runDownloadOnMainThread(ModuleEntry entry, RepoClient.ReleaseInfo target) {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        TextView label = new TextView(this);
        label.setText("准备下载：" + target.assetName);
        root.addView(bar, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(label);

        final AtomicReference<okhttp3.Call> inFlight = new AtomicReference<>();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setView(root)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {
                    okhttp3.Call call = inFlight.get();
                    if (call != null) call.cancel();      // cancel this request only
                    toast("已取消下载", Toast.LENGTH_SHORT);
                })
                .show();

        final File out = new File(downloadDir(this), safeFileName(target.assetName));

        io.execute(() -> {
            try {
                repoClient.download(target.downloadUrl, out, (done, total) -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    main.post(() -> {
                        if (pct >= 0) {
                            bar.setIndeterminate(false);
                            bar.setProgress(pct);
                            label.setText(String.format(Locale.getDefault(),
                                    "%s\n%d%%  (%.1f / %.1f MB)", target.assetName, pct,
                                    done / 1048576.0, total / 1048576.0));
                        } else {
                            label.setText(String.format(Locale.getDefault(),
                                    "%s\n已下载 %.1f MB", target.assetName, done / 1048576.0));
                        }
                    });
                }, inFlight);
                main.post(() -> {
                    dialog.dismiss();
                    showFinished(entry, out);
                });
            } catch (Exception e) {
                final String reason = describe(e);
                final boolean cancelled = isCancellation(e, inFlight);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    if (!cancelled) {
                        toast("下载失败：" + reason);
                    }
                    if (cancelled && out.exists() && !out.delete()) {
                        // Best effort: a truncated zip in the download dir is confusing but harmless.
                    }
                });
            }
        });
    }

    private void showFinished(ModuleEntry entry, File file) {
        String size = String.format(Locale.getDefault(), "%.2f MB", file.length() / 1048576.0);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("下载完成")
                .setMessage(entry.name + "\n" + file.getName() + "\n" + size
                        + "\n\n位置：VioletBox 下载目录\n请到 Magisk / KernelSU / APatch 中刷入。")
                .setNegativeButton("分享到 root 管理器", (d, w) -> shareFile(file))
                .setNeutralButton("关闭", null)
                .setPositiveButton("打开文件", (d, w) -> openFile(file))
                .show();
        dialog.setCanceledOnTouchOutside(true);
    }

    private Uri fileUri(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri(file), guessMime(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "打开文件"));
        } catch (Exception e) {
            toast("没有可以打开此文件的应用：" + describe(e));
        }
    }

    private void shareFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(guessMime(file.getName()));
            intent.putExtra(Intent.EXTRA_STREAM, fileUri(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "发送到 root 管理器"));
        } catch (Exception e) {
            toast("分享失败：" + describe(e));
        }
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".kpm")) return "application/octet-stream";
        return "application/zip";
    }

    private static String safeFileName(String raw) {
        if (TextUtils.isEmpty(raw)) return "module.bin";
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * OkHttp reports cancellation as "Canceled" on IOException, but the message text is the only
     * thing that survives the call boundary. Treat a cancelled Call as a cancellation, and fall
     * back to the message check.
     */
    private static boolean isCancellation(Throwable e, AtomicReference<okhttp3.Call> inFlight) {
        okhttp3.Call call = inFlight.get();
        if (call != null && call.isCanceled()) return true;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.US);
        return lower.contains("canceled") || lower.contains("cancelled");
    }
}
