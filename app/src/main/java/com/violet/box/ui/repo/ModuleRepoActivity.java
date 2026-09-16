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
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.RadioButton;
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
import com.violet.box.core.util.VioletPaths;

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

    /**
     * 远程清单：发不发新 APK 都能更新模块库。
     *
     * 只要往 Buwrt/violet_Box 的 app/src/main/assets/module_repo.json 推一份新清单，
     * 用户下拉刷新（或点右上角刷新）就能拿到，作者更新模块不必重新打包。
     * 之前这里指向的是上游 Smart-Paocai 的仓库 —— 那等于永远在拉别人的清单，已改。
     */
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/Buwrt/violet_Box/main/app/src/main/assets/module_repo.json";

    /** Mirrors of CATALOG_URL, tried in order when the primary host is unreachable. */
    private static final String[] CATALOG_MIRRORS = {
            "https://gh-proxy.com/https://raw.githubusercontent.com/Buwrt/violet_Box/main/app/src/main/assets/module_repo.json",
            "https://raw.gitmirror.com/Buwrt/violet_Box/main/app/src/main/assets/module_repo.json",
            "https://cdn.jsdelivr.net/gh/Buwrt/violet_Box@main/app/src/main/assets/module_repo.json",
    };
    private static final String PREF = "module_repo";
    private static final String KEY_CATALOG_CACHE = "catalog_json";
    private static final String KEY_CATALOG_TIME = "catalog_time";

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

    /** 当前列表是不是已经来自远端清单。true 时内置清单不许再把它覆盖掉。 */
    private volatile boolean remoteFirst;

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

            @Override
            public void onCopyUrl(ModuleEntry entry) {
                copySourceUrl(entry);
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

        // 后台下载要在通知栏显示进度；没授权也能下，只是看不到通知。
        ensureChannel();
        if (android.os.Build.VERSION.SDK_INT >= 33 && !hasNotifyPermission()) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x5642);
        }
        // Android 11+ 分区存储：没有「所有文件访问权限」就写不进 Download/VioletBox。
        // 只在真的写不进去时引导一次（已 root 的设备会自动用 su 建目录，不需要授权）。
        io.execute(() -> {
            if (!VioletPaths.needPermissionGuide()) return;
            main.post(() -> new AlertDialog.Builder(this)
                    .setTitle("需要文件访问权限")
                    .setMessage("模块要保存到\n" + VioletPaths.PUBLIC_ROOT
                            + "\n请允许「所有文件访问权限」，否则会退回到应用私有目录（卸载即丢失）。")
                    .setPositiveButton("去授权", (d, w) -> VioletPaths.requestAllFilesAccess(this))
                    .setNegativeButton("以后再说", null)
                    .show());
        });

        swipe.setOnRefreshListener(this::fetchRemoteCatalog);

        // 打开先显示上一次拉到的远端清单（通常比 APK 内置的新），再去后台静默检查有没有更新
        remoteFirst = applyCatalogPayload(cachedCatalog());
        if (remoteFirst) checkCatalogSilently();
        loadLocalCatalog();
    }

    /** 上一次成功拉取的远端清单原文，没有就返回 null。 */
    private String cachedCatalog() {
        String v = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_CATALOG_CACHE, null);
        return TextUtils.isEmpty(v) ? null : v;
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

    /**
     * 下载目录：固定用公共下载目录下的 VioletBox 文件夹
     * <code>/storage/emulated/0/Download/VioletBox</code>。
     *
     * <p>之前用的是 <code>getExternalFilesDir(DOWNLOADS)</code>，实际落在
     * <code>/storage/emulated/0/Android/data/com.violet.box/files/Download/VioletBox</code> ——
     * 卸载应用就没了，文件管理器里也不好找。现在优先用公共 Download，
     * 拿不到（极少数分区异常）才退回应用私有目录，保证下载不会因为路径问题直接失败。
     */
    static File downloadDir(Context context) {
        return VioletPaths.resolveDir(context, null);
    }

    /** 下载目录的展示用绝对路径，给对话框和提示语用。 */
    private static String downloadDirLabel(Context context) {
        return downloadDir(context).getAbsolutePath();
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
                    // 内置清单坏了但缓存里还有能用的远端清单时，不要清空列表
                    if (allModules.isEmpty()) toast(error);
                    return;
                }
                // 后台拉到的远端清单通常更新、更大，别用内置的把它覆盖掉
                if (!remoteFirst && !allModules.isEmpty()) {
                    applyFilter();
                    return;
                }
                allModules.clear();
                allModules.addAll(result);
                applyFilter();
                // 内置清单落地后，再静默检查一次远端有没有新版本
                if (!remoteFirst) checkCatalogSilently();
            });
        });
    }

    /**
     * 解析一份清单原文并铺到界面上。返回是否成功。
     *
     * @param saveCache 只有「真正从网上拉到的新清单」才写缓存，内置资源不写。
     */
    private boolean applyCatalogPayload(String json) {
        if (TextUtils.isEmpty(json)) return false;
        try {
            JSONArray array = new JSONObject(json).optJSONArray("modules");
            if (array == null || array.length() == 0) throw new Exception("清单为空");
            List<ModuleEntry> result = ModuleEntry.parseList(array);
            if (result.isEmpty()) throw new Exception("清单为空");
            allModules.clear();
            allModules.addAll(result);
            applyFilter();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 静默检查远端清单。
     *
     * <p>这是「作者更新了模块，我这边会不会跟着更新」的答案：
     * <ul>
     *   <li><b>已有模块发了新版本</b> —— 下载时实时查 GitHub Releases，永远拿到最新版，
     *       清单里存的是仓库地址而不是版本号，所以不需要更新清单。</li>
     *   <li><b>清单里新增了新模块 / 新仓库</b> —— 只有往 Buwrt/violet_Box 推一份新的
     *       module_repo.json 才会出现。这里就是那次自动拉取。</li>
     * </ul>
     * 当前版本拉到的清单会存进 SharedPreferences，下次启动直接生效，不必等发新 APK。
     */
    private void checkCatalogSilently() {
        io.execute(() -> {
            String json = fetchFromAnyMirror();
            if (json == null) return;               // 静默失败：保留现有清单，不打扰用户
            if (json.equals(cachedCatalog())) return; // 内容没变，省掉一次界面刷新
            final String payload = json;
            main.post(() -> {
                if (!applyCatalogPayload(payload)) return;
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putString(KEY_CATALOG_CACHE, payload)
                        .putString(KEY_CATALOG_TIME, nowStamp())
                        .apply();
                remoteFirst = true;
                toast("模块清单已更新，共 " + allModules.size() + " 个模块", Toast.LENGTH_SHORT);
            });
        });
    }

    private static String nowStamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new java.util.Date());
    }

    /** 依次尝试主地址和镜像，返回第一份拿到的清单原文；全挂返回 null。 */
    private String fetchFromAnyMirror() {
        for (String url : catalogUrls()) {
            try {
                String json = fetchCatalog(url);
                if (!TextUtils.isEmpty(json)) return json;
            } catch (Exception ignored) {
                // 换下一个镜像
            }
        }
        return null;
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
                    toast("清单更新失败，继续使用当前清单（" + reason + "）", Toast.LENGTH_SHORT);
                });
                return;
            }
            final String payload = json;
            final int before = allModules.size();
            main.post(() -> {
                swipe.setRefreshing(false);
                if (!applyCatalogPayload(payload)) {
                    toast("清单解析失败，继续使用当前清单", Toast.LENGTH_SHORT);
                    return;
                }
                remoteFirst = true;
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putString(KEY_CATALOG_CACHE, payload)
                        .putString(KEY_CATALOG_TIME, nowStamp())
                        .apply();
                int delta = allModules.size() - before;
                toast(delta > 0
                        ? "清单已更新：" + allModules.size() + " 个模块（新增 " + delta + " 个）"
                        : "清单已是最新：" + allModules.size() + " 个模块", Toast.LENGTH_SHORT);
            });
        });
    }

    /** 长按卡片：把模块所在仓库的开源地址放进剪贴板。 */
    private void copySourceUrl(ModuleEntry entry) {
        String url = entry.sourceUrl();
        Object svc = getSystemService(Context.CLIPBOARD_SERVICE);
        if (svc instanceof android.content.ClipboardManager) {
            ((android.content.ClipboardManager) svc)
                    .setPrimaryClip(android.content.ClipData.newPlainText(entry.name, url));
            toast("已复制开源地址：\n" + url, Toast.LENGTH_SHORT);
        } else {
            toast(url, Toast.LENGTH_LONG);
        }
    }

    /**
     * 重新计算列表 + 计数。
     *
     * <p>计数规则（用户明确要求的）：先按选中的分类过滤，再按关键词过滤，
     * 标签上显示的是「当前选择下总共有多少个」，而不是永远显示全库总数。
     * 例如点「KPM 内核模块」就显示 "KPM 内核模块 22 个"。
     */
    private void applyFilter() {
        int checkedId = ((ChipGroup) findViewById(R.id.chipGroupFilter)).getCheckedChipId();
        String[] keys = queryKeys();

        // 先把当前分类的全量算出来（不含关键词过滤），它就是「我选择的总共有多少个」
        List<ModuleEntry> inCategory = new ArrayList<>();
        for (ModuleEntry entry : allModules) {
            if (checkedId == R.id.chipZip && (entry.isKpm() || entry.isLsp())) continue;
            if (checkedId == R.id.chipKpm && !entry.isKpm()) continue;
            if (checkedId == R.id.chipLsp && !entry.isLsp()) continue;
            inCategory.add(entry);
        }

        visible = new ArrayList<>();
        for (ModuleEntry entry : inCategory) {
            if (!matches(entry, keys)) continue;
            visible.add(entry);
        }
        adapter.update(visible);

        boolean none = visible.isEmpty() && !loading.isShown();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (count != null) count.setText(countText(checkedId, inCategory.size(), keys.length));
    }

    /**
     * 计数文案：切换到哪个分类，就只显示那个分类的数量。
     *
     * <p>之前这里会在下面再补一行 "ZIP x · KPM y · LSP z"，用户看成了「不管点哪个都显示同一串数字」。
     * 现在只报当前这一档：切到 ZIP 就只有 ZIP 的数，切到 LSP 就只有 LSP 的数。
     */
    private String countText(int checkedId, int categoryTotal, int keyCount) {
        String label;
        if (checkedId == R.id.chipZip) label = "ZIP 模块";
        else if (checkedId == R.id.chipKpm) label = "KPM 内核模块";
        else if (checkedId == R.id.chipLsp) label = "LSP 模块";
        else label = "全部";

        StringBuilder sb = new StringBuilder();
        sb.append(label).append(' ').append(categoryTotal).append(" 个");
        if (keyCount > 0) {
            // 搜索时补一句命中多少，否则用户不知道列表为什么变短了
            sb.append("  ·  匹配 ").append(visible.size()).append(" 个");
        }
        String stamp = catalogStamp();
        if (!TextUtils.isEmpty(stamp)) sb.append("\n清单更新于 ").append(stamp);
        return sb.toString();
    }

    /** 上次成功从远端拉取清单的时间，没拉过就返回空串。 */
    private String catalogStamp() {
        String v = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_CATALOG_TIME, "");
        return v == null ? "" : v;
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

    /** Row tap: 拉版本列表后弹选择框。默认选中 index 0 == 最新版。 */
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

    /** 「下载最新」按钮：不弹版本框，直接取 index 0。 */
    private void downloadLatest(ModuleEntry entry) {
        startDownload(entry, -1, null);
    }

    /**
     * Row tap: let the user choose a tag.
     *
     * <p>UI notes (this dialog used to be the ugliest thing in the app): the old version used
     * <code>setSingleChoiceItems</code>, which renders one long string per row - the tag, the file
     * name and the size all wrapped onto separate lines, and the radio button was stretched into a
     * vertical oval. Now every row is a real layout: radio + tag + a green "最新版" badge + the
     * asset name + the size, one line each, with the module's identity in a proper header above
     * the list. Default selection is still index 0 == newest release.
     */
    private void showVersionDialog(ModuleEntry entry, List<RepoClient.ReleaseInfo> releases) {
        if (releases.isEmpty()) {
            // This runs on the main thread today, but never call Toast.makeText directly here:
            // the previous build grew this exact call from a background caller and hard-crashed
            // with "Can't create handler inside thread".
            toast("没有可用版本", Toast.LENGTH_SHORT);
            return;
        }
        final int[] selected = {0};

        android.widget.ListView list = new android.widget.ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        list.setAdapter(new ReleaseAdapter(releases, selected));
        list.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        list.setItemChecked(0, true);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected[0] = position;
            ((ReleaseAdapter) list.getAdapter()).notifyDataSetChanged();
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(headerView(entry))
                .setView(list)
                .setNegativeButton("取消", null)
                .setPositiveButton("下载此版本",
                        (d, w) -> startDownload(entry, selected[0], releases))
                .create();

        // 版本多的模块（例如 PlayIntegrityFix 有 20+ 个 tag）让列表自己滚，不要把按钮顶出屏幕
        dialog.setOnShowListener(d -> {
            android.view.Window win = dialog.getWindow();
            if (win == null) return;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            win.setLayout(
                    (int) (dm.widthPixels * 0.92),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setLayout(Math.min((int) (dm.widthPixels * 0.92), dm.widthPixels - 48),
                    (int) (dm.heightPixels * 0.72));
        });
        dialog.show();
    }

    /** 版本 / 下载对话框共用的头部：类型徽章 + 模块名 + 仓库地址。 */
    private View headerView(ModuleEntry entry) {
        View head = LayoutInflater.from(this).inflate(R.layout.dialog_repo_header, null, false);
        TextView type = head.findViewById(R.id.tvDlgType);
        TextView group = head.findViewById(R.id.tvDlgGroup);
        TextView name = head.findViewById(R.id.tvDlgName);
        TextView repo = head.findViewById(R.id.tvDlgRepo);

        type.setText(entry.isKpm() ? "KPM" : entry.isLsp() ? "LSP" : "ZIP");
        Drawable badge = type.getBackground();
        if (badge != null) {
            badge.mutate();
            badge.setTint(getResources().getColor(entry.isKpm()
                    ? R.color.explore_cyan_600
                    : entry.isLsp() ? R.color.explore_emerald_600
                    : R.color.ios_accent));
        }
        if (group != null) group.setText(entry.group);
        name.setText(entry.name);
        repo.setText(entry.owner + "/" + entry.repo);
        return head;
    }

    /** 版本列表适配器：每个 tag 一行卡片式排版，不再把整行拼成一根长字符串。 */
    private final class ReleaseAdapter extends android.widget.BaseAdapter {
        private final List<RepoClient.ReleaseInfo> items;
        private final int[] selected;

        ReleaseAdapter(List<RepoClient.ReleaseInfo> items, int[] selected) {
            this.items = items;
            this.selected = selected;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(ModuleRepoActivity.this)
                    .inflate(R.layout.item_repo_release, parent, false);
            RepoClient.ReleaseInfo r = items.get(position);

            ((RadioButton) row.findViewById(R.id.rbRelease)).setChecked(position == selected[0]);
            ((TextView) row.findViewById(R.id.tvReleaseTag))
                    .setText(TextUtils.isEmpty(r.tag) ? "未命名版本" : r.tag);
            ((TextView) row.findViewById(R.id.tvReleaseAsset)).setText(r.assetName);

            TextView meta = row.findViewById(R.id.tvReleaseMeta);
            meta.setText(r.size > 0
                    ? String.format(Locale.getDefault(), "%.2f MB", r.size / 1048576.0)
                    : "体积未知");

            row.findViewById(R.id.tvReleaseNewest)
                    .setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            return row;
        }
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
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_repo_download, null, false);
        ProgressBar bar = content.findViewById(R.id.pbDl);
        TextView file = content.findViewById(R.id.tvDlFile);
        TextView percent = content.findViewById(R.id.tvDlPercent);
        TextView size = content.findViewById(R.id.tvDlSize);
        TextView hint = content.findViewById(R.id.tvDlHint);

        file.setText(target.assetName);
        percent.setText("0%");
        size.setText(target.size > 0
                ? String.format(Locale.getDefault(), "0.00 / %.2f MB", target.size / 1048576.0)
                : "");

        final AtomicReference<okhttp3.Call> inFlight = new AtomicReference<>();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(headerView(entry))
                .setView(content)
                .setCancelable(true)
                .setNegativeButton("后台下载", (d, w) -> {
                    // 关掉对话框不等于取消——下载线程继续跑，进度转到通知栏。
                    // 真正想取消要点「取消下载」按钮。
                    toast("已转到后台，下载继续中", Toast.LENGTH_SHORT);
                })
                .setNeutralButton("取消下载", (d, w) -> {
                    okhttp3.Call call = inFlight.get();
                    if (call != null) call.cancel();      // cancel this request only
                    cancelNotify();
                    toast("已取消下载", Toast.LENGTH_SHORT);
                })
                .show();
        // 用户按返回键 / 点对话框外面同样只关界面，不停任务
        dialog.setOnCancelListener(d -> toast("已转到后台，下载继续中", Toast.LENGTH_SHORT));

        final File out = new File(downloadDir(this), safeFileName(target.assetName));

        io.execute(() -> {
            try {
                repoClient.download(target.downloadUrl, out, (done, total) -> {
                    long totalBytes = total > 0 ? total : target.size;
                    int pct = totalBytes > 0 ? (int) (done * 100 / totalBytes) : -1;
                    main.post(() -> {
                        if (pct >= 0) {
                            bar.setIndeterminate(false);
                            bar.setProgress(pct);
                            percent.setText(pct + "%");
                            size.setText(totalBytes > 0
                                    ? String.format(Locale.getDefault(), "%.2f / %.2f MB",
                                    done / 1048576.0, totalBytes / 1048576.0)
                                    : String.format(Locale.getDefault(), "已下载 %.2f MB",
                                    done / 1048576.0));
                            hint.setText("可离开本页面，下载会在后台继续");
                        } else {
                            percent.setText("下载中");
                            size.setText(String.format(Locale.getDefault(),
                                    "已下载 %.2f MB", done / 1048576.0));
                        }
                    });
                    // 后台下载：进度同步到通知栏，用户切走 / 锁屏也能看到
                    notifyProgress(target.assetName, pct, done, total > 0 ? total : target.size);
                }, inFlight);
                notifyDone("下载完成", target.assetName);
                // 兜底：如果因为分区存储写到了私有目录，下载完再用 root 搬到公共目录
                final File published = VioletPaths.publishToPublic(ModuleRepoActivity.this, out, null);
                main.post(() -> {
                    dialog.dismiss();
                    // 用户可能已经离开本页面，通知栏就是主入口；回到页面才弹完成框。
                    if (isFinishing() || isDestroyed()) return;
                    showFinished(entry, published);
                });
            } catch (Exception e) {
                final String reason = describe(e);
                final boolean cancelled = isCancellation(e, inFlight);
                if (cancelled) cancelNotify();
                else notifyDone("下载失败", target.assetName + "：" + reason);
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

    // ------------------------------------------------------------------ 下载通知
    // 后台下载靠通知栏兜住进度：离开当前页面、锁屏、切到别的应用都还能看到，
    // 下载完成 / 失败也在这里给结果，不需要用户回到本页面。

    private static final String CH_DOWNLOAD = "violet_download";
    private static final int NOTIFY_ID = 0x5642;      // "VB"

    private void ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH_DOWNLOAD) != null) return;
        android.app.NotificationChannel ch = new android.app.NotificationChannel(
                CH_DOWNLOAD, "模块下载", android.app.NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("模块下载进度与结果");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private android.app.Notification.Builder builder() {
        ensureChannel();
        android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(this, CH_DOWNLOAD)
                : new android.app.Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setContentTitle("紫罗兰Box")
                .setContentIntent(android.app.PendingIntent.getActivity(
                        this, 0, new Intent(this, ModuleRepoActivity.class),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE));
    }

    private void notifyProgress(String name, int pct, long done, long total) {
        if (!hasNotifyPermission()) return;
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        String text = total > 0
                ? String.format(Locale.getDefault(), "%.2f / %.2f MB", done / 1048576.0, total / 1048576.0)
                : String.format(Locale.getDefault(), "已下载 %.2f MB", done / 1048576.0);
        android.app.Notification.Builder b = builder()
                .setContentText(name + "  " + text)
                .setProgress(100, Math.max(0, pct), pct < 0);
        nm.notify(NOTIFY_ID, b.build());
    }

    private void notifyDone(String title, String text) {
        if (!hasNotifyPermission()) return;
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIFY_ID, builder()
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .build());
    }

    private void cancelNotify() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFY_ID);
    }

    /** Android 13+ 要用户授权通知；没授权就静默跳过，不能因此让下载失败。 */
    private boolean hasNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void showFinished(ModuleEntry entry, File file) {
        String size = String.format(Locale.getDefault(), "%.2f MB", file.length() / 1048576.0);
        boolean apk = file.getName().toLowerCase(Locale.US).endsWith(".apk");

        // LSP 模块是 Xposed 模块 apk，走系统安装器；ZIP / KPM 走 root 管理器刷入。
        String body = entry.name + "\n" + file.getName() + "\n" + size
                + "\n\n保存位置：" + downloadDirLabel(this) + "/"
                + "\n" + (apk ? "这是 LSPosed 模块，点「安装」交给系统安装器。"
                : "请到 Magisk / KernelSU / APatch 中刷入。");

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("下载完成")
                .setMessage(body)
                .setNeutralButton("关闭", null)
                .setNegativeButton("分享到 root 管理器", (d, w) -> shareFile(file))
                .setPositiveButton(apk ? "安装" : "打开文件",
                        (d, w) -> {
                            if (apk) installApk(file);
                            else openFile(file);
                        });
        AlertDialog dialog = b.show();
        dialog.setCanceledOnTouchOutside(true);
    }

    /** 拉起系统安装器安装刚下载的 LSPosed 模块 apk。 */
    private void installApk(File file) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    && !getPackageManager().canRequestPackageInstalls()) {
                toast("请先允许本应用「安装未知应用」后再试", Toast.LENGTH_LONG);
                Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                settings.setData(Uri.parse("package:" + getPackageName()));
                startActivity(settings);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri(file), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("无法拉起安装器：" + describe(e));
        }
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
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
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
