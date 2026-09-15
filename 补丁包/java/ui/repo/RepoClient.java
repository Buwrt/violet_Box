package com.violet.box.ui.repo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin GitHub Releases client.
 *
 * Deliberately built on top of the OkHttp instance already shipped by PayloadCore, so this
 * feature adds ZERO new Gradle dependencies - you can sync the project exactly as it is.
 *
 * Anonymous GitHub API is rate limited to 60 requests/hour per IP. When the quota is gone the
 * API answers HTTP 403 and we surface that to the user instead of failing silently.
 */
final class RepoClient {

    /** A resolved download candidate coming from one release asset. */
    static final class ReleaseInfo {
        final String tag;
        final String title;
        final String assetName;
        final String downloadUrl;
        final long size;

        ReleaseInfo(String tag, String title, String assetName, String downloadUrl, long size) {
            this.tag = tag;
            this.title = title;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }

        @Override
        public String toString() {
            return size > 0
                    ? String.format(Locale.getDefault(), "%s  ·  %s  ·  %.1f MB", tag, assetName, size / 1048576.0)
                    : tag + "  ·  " + assetName;
        }
    }

    static final class RepoException extends Exception {
        final boolean rateLimited;

        RepoException(String message, boolean rateLimited) {
            super(message);
            this.rateLimited = rateLimited;
        }
    }

    private static final String API = "https://api.github.com/repos/%s/%s/releases?per_page=100";
    private static final String UA = "violet-box-module-repo";

    /**
     * Hosts that proxy github.com. The first entry is the direct URL; the rest rewrite
     * <code>https://github.com/...</code>. <code>objects.githubusercontent.com</code> redirects
     * are followed automatically. Order matters: direct first, then the fastest mirrors.
     */
    private static final String[] DOWNLOAD_MIRROR_PREFIXES = {
            "",
            "https://gh-proxy.com/",
            "https://ghfast.top/",
    };

    /** Candidate URLs for one asset, in the order they should be tried. */
    static List<String> mirrorCandidates(String httpUrl) {
        List<String> out = new ArrayList<>();
        for (String prefix : DOWNLOAD_MIRROR_PREFIXES) {
            out.add(prefix + httpUrl);
        }
        return out;
    }

    private final OkHttpClient client;

    RepoClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    /**
     * Fetch every release of a repository and keep only the assets matching the catalog suffix
     * (e.g. ".zip" / ".kpm"). The newest release GitHub reports first becomes index 0, which is
     * what we hand out as "latest" when the user does not pick anything.
     */
    List<ReleaseInfo> listReleases(ModuleEntry entry) throws Exception {
        String url = String.format(API, entry.owner, entry.repo);
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", UA)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 403 || response.code() == 429) {
                throw new RepoException("GitHub API 限速（每小时 60 次），请稍后再试", true);
            }
            if (!response.isSuccessful()) {
                throw new RepoException("拉取版本列表失败：HTTP " + response.code(), false);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new RepoException("响应为空", false);
            }
            String json = body.string();
            return filterReleases(new JSONArray(json), entry);
        }
    }

    /**
     * newest -> oldest; keeps one asset per release.
     *
     * <p>Pick order inside one release:
     * <ol>
     *   <li>suffix matches AND the catalog keyword appears in the file name</li>
     *   <li>any suffix match at all</li>
     *   <li>if nothing matched the suffix anywhere, a second pass accepts any asset that is a
     *       plausible flashable package - releases routinely ship names like
     *       <code>Shamiko-v1.2.5-414-release.zip</code> under a repo that also holds unrelated
     *       files, and refusing to guess is worse than showing the user the real list.</li>
     * </ol>
     */
    private List<ReleaseInfo> filterReleases(JSONArray releases, ModuleEntry entry) throws Exception {
        String suffix = entry.assetSuffix.toLowerCase(Locale.US);
        String keyword = entry.assetKeyword == null ? "" : entry.assetKeyword.toLowerCase(Locale.US);

        List<ReleaseInfo> strict = collect(releases, suffix, keyword, false);
        if (!strict.isEmpty()) return Collections.unmodifiableList(strict);

        List<ReleaseInfo> loose = collect(releases, suffix, keyword, true);
        if (loose.isEmpty()) {
            throw new RepoException("该仓库最新发布里没有可下载的 "
                    + entry.assetSuffix + " 资源，请稍后重试或到 github.com/"
                    + entry.owner + "/" + entry.repo + " 手动下载", false);
        }
        return Collections.unmodifiableList(loose);
    }

    private List<ReleaseInfo> collect(JSONArray releases, String suffix, String keyword,
                                      boolean acceptAnyArchive) {
        List<ReleaseInfo> out = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject rel = releases.optJSONObject(i);
            if (rel == null) continue;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null || assets.length() == 0) continue;

            JSONObject chosen = null;
            for (int a = 0; a < assets.length(); a++) {
                JSONObject asset = assets.optJSONObject(a);
                if (asset == null) continue;
                String fileName = asset.optString("name", "");
                String lower = fileName.toLowerCase(Locale.US);
                boolean suffixHit = lower.endsWith(suffix);
                boolean archiveHit = acceptAnyArchive && (lower.endsWith(".zip") || lower.endsWith(".kpm"));
                if (!suffixHit && !archiveHit) continue;
                if (chosen == null) chosen = asset;
                if (!keyword.isEmpty() && lower.contains(keyword)) {
                    chosen = asset;      // explicit catalog hint wins
                    break;
                }
            }
            if (chosen == null) continue;

            out.add(new ReleaseInfo(
                    rel.optString("tag_name", rel.optString("name", "?")),
                    rel.optString("name", ""),
                    chosen.optString("name", ""),
                    chosen.optString("browser_download_url", ""),
                    chosen.optLong("size", 0L)
            ));
        }
        return out;
    }

    /**
     * Streaming download to a caller supplied file.
     *
     * <p>Tries the direct GitHub URL first, then each mirror, so a blocked
     * <code>objects.githubusercontent.com</code> does not look like a broken module.
     *
     * <p>The in-flight <code>Call</code> is published through <code>callRef</code> so the caller
     * can cancel it. We must NOT cancel by shutting the executor down - that would kill the shared
     * thread every later request depends on.
     */
    void download(String httpUrl, java.io.File target, ProgressListener listener,
                  java.util.concurrent.atomic.AtomicReference<Call> callRef) throws Exception {
        Exception last = null;
        for (String candidate : mirrorCandidates(httpUrl)) {
            try {
                downloadOnce(candidate, target, listener, callRef);
                return;
            } catch (Exception e) {
                last = e;
                if (isCancelled(e, callRef)) throw e;   // user pressed cancel: stop, do not retry
            }
        }
        throw last == null ? new RepoException("下载失败", false) : last;
    }

    private void downloadOnce(String url, java.io.File target, ProgressListener listener,
                              java.util.concurrent.atomic.AtomicReference<Call> callRef)
            throws Exception {
        Request request = new Request.Builder().url(url).header("User-Agent", UA).build();
        Call call = client.newCall(request);
        if (callRef != null) callRef.set(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new RepoException("下载失败：HTTP " + response.code(), false);
            }
            ResponseBody body = response.body();
            if (body == null) throw new RepoException("下载响应为空", false);

            long total = body.contentLength();
            java.io.InputStream in = body.byteStream();
            java.io.File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new RepoException("无法创建下载目录", false);
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0L;
                int read;
                while ((read = in.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    done += read;
                    if (listener != null) listener.onProgress(done, total);
                }
                fos.flush();
            }
        }
    }

    private static boolean isCancelled(Throwable e, java.util.concurrent.atomic.AtomicReference<Call> callRef) {
        Call call = callRef == null ? null : callRef.get();
        if (call != null && call.isCanceled()) return true;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.US);
        return lower.contains("canceled") || lower.contains("cancelled");
    }

    interface ProgressListener {
        void onProgress(long done, long total);
    }
}
