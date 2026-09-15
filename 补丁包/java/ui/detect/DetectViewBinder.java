package com.violet.box.ui.detect;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.scottyab.rootbeer.RootBeer;
import com.violet.box.R;
import com.violet.box.data.detector.RootDetector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders the environment-detection panel restored from b42df36.
 *
 * <p>The screen is deliberately split in two: the seven RootBeer baseline checks the original
 * DetectFragment shipped, and the fifteen project-specific checks from {@link RootDetector}.
 * Everything runs off the main thread - RootDetector shells out to getprop/cat, which must never
 * block a frame.
 */
public final class DetectViewBinder {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "env-detect"));

    /** One row of the report: a Chinese label, whether it tripped, and the raw hits. */
    public static final class Check {
        public final String key;
        public final String label;
        public final List<String> hits;
        final boolean informational;

        Check(String key, String label, List<String> hits, boolean informational) {
            this.key = key;
            this.label = label;
            this.hits = hits;
            this.informational = informational;
        }

        public boolean tripped() {
            return !hits.isEmpty();
        }
    }

    private DetectViewBinder() {
    }

    /**
     * Wire up an already-inflated {@code fragment_detect} view. Safe to call more than once;
     * the re-run button re-dispatches the whole sweep.
     *
     * @param root view containing R.id.tvRootStatus / layoutRootBeerDetails / layoutDetectionDetails
     */
    public static void bind(View root, Context context) {
        if (root == null || context == null) return;
        final Context app = context.getApplicationContext();
        TextView tvStatus = root.findViewById(R.id.tvRootStatus);
        LinearLayout beerHost = root.findViewById(R.id.layoutRootBeerDetails);
        LinearLayout deepHost = root.findViewById(R.id.layoutDetectionDetails);
        LinearLayout adviceHost = root.findViewById(R.id.layoutDetectAdvice);
        View adviceCard = root.findViewById(R.id.cardDetectAdvice);

        // No re-run button: the restored layout matches b42df36 exactly. Re-scanning is triggered
        // by the host (pull-to-refresh on the safety tab), not by a control inside the panel.
        run(app, tvStatus, beerHost, deepHost, adviceHost, adviceCard);
    }

    private static void run(Context app, TextView tvStatus, LinearLayout beerHost,
                            LinearLayout deepHost, LinearLayout adviceHost, View adviceCard) {
        if (tvStatus != null) {
            tvStatus.setText("正在检测…");
            tvStatus.setTextColor(ContextCompat.getColor(app, R.color.ios_text_secondary));
        }
        if (beerHost != null) beerHost.removeAllViews();
        if (deepHost != null) deepHost.removeAllViews();
        if (adviceHost != null) adviceHost.removeAllViews();
        if (adviceCard != null) adviceCard.setVisibility(View.GONE);

        IO.execute(() -> {
            // ---- baseline -------------------------------------------------------------
            List<Check> beer = new ArrayList<>();
            RootBeer rootBeer = new RootBeer(app);
            boolean rooted;
            try {
                rooted = rootBeer.isRooted();
            } catch (Throwable t) {
                rooted = false;
            }
            beer.add(new Check("testkeys", "测试签名", bool(rootBeer.detectTestKeys()), false));
            beer.add(new Check("mgmt", "Root 管理应用", bool(rootBeer.detectRootManagementApps()), false));
            beer.add(new Check("cloak", "Root 隐藏应用", bool(rootBeer.detectRootCloakingApps()), false));
            beer.add(new Check("danger", "危险应用", bool(rootBeer.detectPotentiallyDangerousApps()), false));
            beer.add(new Check("su", "SU 二进制文件", bool(rootBeer.checkForSuBinary()), false));
            beer.add(new Check("magisk", "Magisk", bool(rootBeer.checkForMagiskBinary()), false));
            beer.add(new Check("busybox", "BusyBox", bool(rootBeer.checkForBusyBoxBinary()), false));

            // ---- deep -----------------------------------------------------------------
            List<Check> deep = new ArrayList<>();
            RootDetector detector = new RootDetector(app);
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("su", "SU 二进制");
            labels.put("pkg", "Root 相关应用");
            labels.put("magisk", "Magisk / KSU 路径");
            labels.put("bin", "危险二进制");
            labels.put("module", "隐藏 / 绕过模块");
            labels.put("mount", "可疑挂载点");
            labels.put("prop", "危险系统属性");
            labels.put("runtime", "运行时注入痕迹");
            labels.put("tmpfs", "/data 上的 tmpfs");
            labels.put("mtime", "Root 目录近期改动");
            labels.put("lineage", "LineageOS 特征");
            labels.put("rom", "自定义 ROM");
            labels.put("coherence", "Build 字段一致性");
            labels.put("adv", "高级运行时检测");
            labels.put("overlay", "OverlayFS 系统改写");
            labels.put("installer", "安装来源");

            add(deep, "su", labels, safe(detector::checkSuBinaries));
            add(deep, "pkg", labels, safe(detector::checkRootPackages));
            add(deep, "magisk", labels, safe(detector::checkMagiskPaths));
            add(deep, "bin", labels, safe(detector::checkDangerousBinaries));
            add(deep, "module", labels, safe(detector::checkHideBypassModules));
            add(deep, "mount", labels, safe(detector::checkMountPoints));
            add(deep, "prop", labels, safe(detector::checkDangerousProperties));
            add(deep, "runtime", labels, safe(detector::checkRuntimeArtifacts));
            add(deep, "tmpfs", labels, safe(detector::checkTmpfsOnData));
            add(deep, "mtime", labels, safe(detector::checkSuTimestamps));
            add(deep, "lineage", labels, safe(detector::checkLineageOS));
            add(deep, "rom", labels, safe(detector::checkCustomRom));
            add(deep, "coherence", labels, safe(detector::checkBuildFieldCoherence));
            add(deep, "adv", labels, safe(detector::checkAdvancedRuntime));
            add(deep, "overlay", labels, safe(detector::checkOverlayFS));
            add(deep, "installer", labels, safe(detector::checkApkInstallSource));

            final List<String> advice = buildAdvice(deep);
            final boolean isRooted = rooted;
            final int hitCount = countHits(deep);
            post(app, () -> render(app, tvStatus, beerHost, deepHost, adviceHost, adviceCard,
                    beer, deep, advice, isRooted, hitCount));
        });
    }

    private static void post(Context app, Runnable task) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
    }

    private static void render(Context app, TextView tvStatus, LinearLayout beerHost,
                               LinearLayout deepHost, LinearLayout adviceHost, View adviceCard,
                               List<Check> beer, List<Check> deep, List<String> advice,
                               boolean isRooted, int hitCount) {
        int negative = ContextCompat.getColor(app, R.color.ios_semantic_negative);
        int positive = ContextCompat.getColor(app, R.color.ios_semantic_positive);

        if (tvStatus != null) {
            // Wording matches the b42df36 DetectFragment verbatim.
            if (isRooted || hitCount > 0) {
                tvStatus.setText("⚠️ 检测到 Root 痕迹");
                tvStatus.setTextColor(negative);
            } else {
                tvStatus.setText("✅ 设备安全（未检测到 Root）");
                tvStatus.setTextColor(positive);
            }
        }
        if (beerHost != null) for (Check c : beer) addRow(app, beerHost, c, positive, negative);
        if (deepHost != null) for (Check c : deep) addRow(app, deepHost, c, positive, negative);
        if (adviceHost != null && adviceCard != null && !advice.isEmpty()) {
            adviceCard.setVisibility(View.VISIBLE);
            for (String line : advice) {
                TextView tv = new TextView(app);
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                tv.setPadding(0, 6, 0, 6);
                tv.setTextSize(13);
                tv.setLineSpacing(4f, 1f);
                tv.setText(line);
                tv.setTextColor(ContextCompat.getColor(app, R.color.ios_text_secondary));
                adviceHost.addView(tv);
            }
        }
    }

    private static void addRow(Context app, LinearLayout parent, Check check,
                               int positive, int negative) {
        TextView tv = new TextView(app);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setPadding(0, 8, 0, 8);
        tv.setTextSize(14);
        if (check.tripped()) {
            // b42df36 row format: "⚠️ <label>: 发现异常". The hit count is appended so a category
            // with many findings is distinguishable from a single stray hit.
            String tail = check.hits.size() > 1 ? "（" + check.hits.size() + " 项）" : "";
            tv.setText("⚠️ " + check.label + ": 发现异常" + tail);
            tv.setTextColor(check.informational
                    ? ContextCompat.getColor(app, R.color.ios_text_secondary) : negative);
        } else {
            tv.setText("✅ " + check.label + ": 正常");
            tv.setTextColor(positive);
        }
        parent.addView(tv);
    }

    private static int countHits(List<Check> checks) {
        int n = 0;
        for (Check c : checks) if (c.tripped() && !c.informational) n++;
        return n;
    }

    private static List<String> bool(boolean detected) {
        List<String> out = new ArrayList<>();
        if (detected) out.add("命中");
        return out;
    }

    private static void add(List<Check> out, String key, Map<String, String> labels, List<String> hits) {
        String label = labels.get(key);
        out.add(new Check(key, label == null ? key : label, hits, "installer".equals(key)));
    }

    /** Never let one broken check kill the whole sweep. */
    private static List<String> safe(java.util.concurrent.Callable<List<String>> call) {
        try {
            List<String> r = call.call();
            return r == null ? new ArrayList<>() : r;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * Turn findings into "flash what" guidance. Keyed by the same categories RootDetector uses.
     */
    private static List<String> buildAdvice(List<Check> deep) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> tripped = new java.util.HashSet<>();
        for (Check c : deep) if (c.tripped()) tripped.add(c.key);
        java.util.function.Predicate<String> has = tripped::contains;

        if (has.test("su") || has.test("magisk") || has.test("bin") || has.test("pkg")) {
            out.add("① 基础痕迹（su / magisk 路径 / 管理应用）暴露：把「设置 → 排除列表 / DenyList」"
                    + "打开并勾选检测方应用；Magisk 建议开启「遵守排除列表」+ 隐藏 Magisk 应用；"
                    + "APatch / KernelSU 用自带的「排除列表 / 卸载」模块处理。");
        }
        if (has.test("mount") || has.test("overlay") || has.test("tmpfs")) {
            out.add("② 挂载点被改写：优先切到 OverlayFS 方案并刷入 SUSFS 内核模块（配合 susfs4ksu 模块），"
                    + "或在 Magisk 里关掉 OverlayFS 改回 magic mount，再确认 /data/adb 上没有裸 tmpfs。");
        }
        if (has.test("prop") || has.test("rom") || has.test("lineage") || has.test("coherence")) {
            out.add("③ 属性 / ROM 指纹不匹配：刷 PlayIntegrityFix（PIF，优先 inject 模式，"
                    + "props 模式需配合自定义 pif.json）；自定义 ROM 还需补 ro.build.fingerprint 一致性，"
                    + "否则 DEVICE 完整性过不了。");
        }
        if (has.test("runtime") || has.test("adv")) {
            out.add("④ 运行时痕迹（maps / zygote / 环境变量）：Zygisk 侧换 ZygiskNext、ReZygisk 或 NeoZygisk，"
                    + "并叠加 Shamiko 处理 DenyList 仍需加载的场景；/proc/kallsyms 可读要靠 SUSFS 隐藏。");
        }
        if (has.test("module")) {
            out.add("⑤ 已装隐藏类模块但仍被检出：多数是模块作用域没覆盖到检测方，"
                    + "检查模块的 target / scope 配置，而不是继续叠加更多模块。");
        }
        if (out.isEmpty()) {
            out.add("未发现需要处理的环境异常。若第三方应用仍报环境不合格，一般是它自己的强校验"
                    + "（如 STRONG 完整性需要有效 keybox），可在「模块下载」里取 TrickyStore / TEESimulator 进一步处理。");
        } else {
            out.add("说明：以上为按检出类别给出的通用处置方向，模块下载页可按需取用；"
                    + "KPM 模块需要内核级方案（KernelPatch / KPatch-Next）才能加载。");
        }
        return out;
    }
}
