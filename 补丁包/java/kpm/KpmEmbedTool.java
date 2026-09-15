package com.violet.box.kpm;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes KPMs <em>into</em> the boot image - the "Embed" route that APatch/FolkPatch shows as
 * 「已嵌入」 - by re-running the very same kptools pipeline those managers use.
 *
 * <h2>Why this works and what it deliberately does not do</h2>
 *
 * A KernelPatch patched kernel image is laid out by {@code patch_update_img_buf()} as
 *
 * <pre>
 *   [original kernel][kpimg][128-byte "kpe" extra chain]
 * </pre>
 *
 * so adding or removing an embedded KPM means regenerating that whole image with
 * {@code kptools -p -i kernel.ori -k kpimg -o kernel -M <file> -T kpm -E <keep> ...} and then
 * repacking it into the boot image. Everything needed for that already exists on the device:
 *
 * <ul>
 *   <li><b>kptools</b> ships with APatch at {@code /data/adb/ap/bin/kptools};</li>
 *   <li><b>kpimg</b> is already inside the user's own boot image ({@link KpmPreset} carves it out),
 *       which guarantees a version match instead of shipping another binary;</li>
 *   <li><b>the superkey material</b> is already in the image too, so we preserve it byte-for-byte
 *       and never have to ask the user for their key.</li>
 * </ul>
 *
 * <p>The last point is the important one. kptools writes either a plaintext superkey
 * ({@code -s}) or the SHA256 of it ({@code -S}), and there is no way to re-enter the old key
 * without knowing it. So instead we let kptools write a throwaway key and afterwards copy the old
 * {@code setup.superkey} (64 bytes) and {@code setup.root_superkey} (32 bytes) straight over the
 * new ones. Root authorisation is therefore bit-identical before and after - see
 * {@code kernel/base/predata.c: auth_superkey()}.
 *
 * <p>Extras that are re-patched must be re-declared with {@code -E <name>} or they are dropped,
 * which is why the whole flow starts by asking kptools what is currently embedded.
 *
 * <p>Safety, in order: nothing is flashed until the rebuilt image has been unpacked and listed
 * again; the result must be smaller than the partition; the original image is copied to
 * {@code /sdcard/Download/VioletBox} first; and every step can be run in "generate only" mode
 * where the app never touches the boot partition.
 */
public final class KpmEmbedTool {

    /** Root-owned scratch directory. Files are regenerated per operation. */
    public static final String WORK = "/data/local/tmp/violetbox_embed";
    /** User visible backup location - the one place a rescue file is actually useful. */
    public static final String BACKUP_DIR = "/sdcard/Download/VioletBox";

    /** Per-extra cap we are willing to embed; a KPM ELF is normally tens to hundreds of KB. */
    private static final long MAX_EXTRA_FILE = 32L * 1024 * 1024;

    private KpmEmbedTool() {
    }

    // ------------------------------------------------------------------ model

    /** One embedded extra item, exactly as kptools reports it ({@code kptools -i kernel -l}). */
    public static final class Extra {
        public int index;
        public String type = "";
        public String name = "";
        public String event = "";
        public String args = "";
        public int priority;
        public long argsSize;
        public long conSize;
        public String version = "";
        public String author = "";
        public String description = "";
    }

    /** Snapshot of the boot image we are about to modify. */
    public static final class Inspect {
        public boolean ok;
        public String error = "";
        public final StringBuilder log = new StringBuilder();
        public String kptools = "";
        public String device = "";
        public String workDir = WORK;
        /** null when no KernelPatch patch was found. */
        public KpmPreset.Info preset;
        public List<Extra> extras = new ArrayList<>();
        /** Carved KPM payloads (one file per embedded KPM), ready for export/backup. */
        public List<KpmInfo> kpms = new ArrayList<>();
        public boolean kallsyms = true;
        /** Bytes kptools says the extra chain occupies, vs. what it actually listed. */
        public long declaredExtraSize;
        public long listedExtraSize;

        public String versionText() {
            return preset == null ? "" : preset.versionText();
        }
    }

    public static final class Result {
        public boolean ok;
        public String error = "";
        public final StringBuilder log = new StringBuilder();
        /** Path of the rebuilt boot image inside {@link #WORK}. Empty when not built. */
        public String newImage = "";
        /** Devices the image was written to. */
        public List<String> flashedTo = new ArrayList<>();
        /** Rescue copy in {@link #BACKUP_DIR}. */
        public String backup = "";
    }

    // ------------------------------------------------------------- discovery

    /** Known locations of the KernelPatch tools binary; APatch ships it here. */
    private static final String[] KPTOOLS_CANDIDATES = {
            "/data/adb/ap/bin/kptools",
            "/data/adb/kp/bin/kptools",
            "/data/adb/apatch/kptools",
    };

    public static String findKptools() {
        for (String p : KPTOOLS_CANDIDATES) {
            if (KpmShell.exec("[ -x '" + q(p) + "' ]").ok()) return p;
        }
        return null;
    }

    // --------------------------------------------------------------- inspect

    /**
     * Dumps the boot-related partition, unpacks it, reads what is embedded today and carves every
     * embedded KPM into {@code outDir}. Nothing is modified.
     */
    public static Inspect inspect(File outDir) {
        return inspect(outDir, null);
    }

    public static Inspect inspect(File outDir, String preferDevice) {
        Inspect st = new Inspect();
        if (!KpmShell.haveRoot()) {
            st.error = "未获取 ROOT 权限，无法读取 boot 分区";
            return st;
        }
        String kptools = findKptools();
        if (kptools == null) {
            st.error = "未找到 kptools（/data/adb/ap/bin/kptools），需要 APatch/KernelPatch 环境";
            return st;
        }
        st.kptools = kptools;

        List<String> devs = new ArrayList<>();
        if (preferDevice != null) devs.add(preferDevice);
        for (String d : KpmShell.candidateBootDevices()) {
            if (!devs.contains(d)) devs.add(d);
        }

        int tried = 0;
        for (String dev : devs) {
            if (tried >= 3) break;
            long size = KpmShell.deviceSize(dev);
            if (size > 0 && size > 512L * 1024 * 1024) continue;
            tried++;

            String dump = "rm -rf '" + q(WORK) + "'; mkdir -p '" + q(WORK) + "'; "
                    + "cd '" + q(WORK) + "' || exit 9; "
                    + "dd if='" + q(dev) + "' of='" + q(WORK) + "/boot.img' bs=4096 2>/dev/null; "
                    + "echo DUMP_RC=$?";
            KpmShell.Result r = KpmShell.exec(dump, 180000);
            st.log.append("$ dd ").append(dev).append(" -> ").append(r.out.trim()).append('\n');
            if (!r.out.contains("DUMP_RC=0")) continue;
            if (KpmShell.fileSize(WORK + "/boot.img") <= 0) continue;

            String unpack = "cd '" + q(WORK) + "' && '" + q(kptools) + "' unpack boot.img 2>&1; "
                    + "echo UNPACK_RC=$?";
            KpmShell.Result u = KpmShell.exec(unpack, 180000);
            st.log.append("$ kptools unpack -> ").append(u.out.trim()).append('\n');
            if (!u.out.contains("UNPACK_RC=0")) continue;
            if (KpmShell.fileSize(WORK + "/kernel") <= 0) continue;

            // Is this partition actually KernelPatch patched? Same check kptools itself does.
            InputStream in = KpmShell.openRead(WORK + "/kernel");
            if (in != null) {
                try {
                    st.preset = KpmPreset.scan(in, null);
                } catch (Exception ignored) {
                } finally {
                    close(in);
                }
            }
            if (st.preset == null) {
                st.log.append("# ").append(dev).append(" 没有 KernelPatch 补丁，换下一个\n");
                continue;
            }

            st.ok = true;
            st.device = dev;
            st.declaredExtraSize = st.preset.extraSize;

            KpmShell.Result l = KpmShell.exec(
                    "cd '" + q(WORK) + "' && '" + q(kptools) + "' -i kernel -l 2>&1", 60000);
            st.extras = parseExtras(l.out);
            st.log.append(l.out.trim()).append('\n');

            KpmShell.Result f = KpmShell.exec(
                    "cd '" + q(WORK) + "' && '" + q(kptools) + "' -i kernel -f 2>&1", 60000);
            st.kallsyms = f.out.contains("CONFIG_KALLSYMS=y");

            for (Extra e : st.extras) st.listedExtraSize += 128 + e.argsSize + e.conSize;
            st.listedExtraSize += 128; // chain terminator

            // Carve every embedded payload so the UI can show/export/backup real .kpm files.
            InputStream in2 = KpmShell.openRead(WORK + "/kernel");
            if (in2 != null) {
                try {
                    for (KpmEmbedded.Item it : KpmEmbedded.scan(in2, outDir)) {
                        if (it.type == KpmEmbedded.TYPE_KPM && it.info != null) st.kpms.add(it.info);
                    }
                } catch (Exception ignored) {
                } finally {
                    close(in2);
                }
            }
            return st;
        }
        st.error = tried == 0 ? "没有找到可用的 boot / init_boot 分区"
                : "boot / init_boot 镜像里没有 KernelPatch 补丁（当前分区未打补丁）";
        return st;
    }

    // ----------------------------------------------------------------- patch

    /**
     * Rebuilds the boot image.
     *
     * @param st       snapshot from {@link #inspect}
     * @param add      KPM files to embed (may be empty)
     * @param remove   names of embedded extras to drop (may be empty)
     * @param flash    write the result back to the boot partition
     * @param bothSlots also write to the inactive slot when one exists
     */
    public static Result patch(Inspect st, List<File> add, List<String> remove,
                               boolean flash, boolean bothSlots) {
        Result res = new Result();
        if (!st.ok) {
            res.error = st.error;
            return res;
        }
        if (!st.kallsyms) {
            res.error = "内核未开启 CONFIG_KALLSYMS，KernelPatch 无法工作，操作已中止";
            return res;
        }
        if (add.isEmpty() && remove.isEmpty()) {
            res.error = "没有要嵌入或移除的模块";
            return res;
        }
        for (File f : add) {
            if (!f.isFile() || f.length() <= 0) {
                res.error = "文件不可读：" + f.getName();
                return res;
            }
            if (f.length() > MAX_EXTRA_FILE) {
                res.error = "文件过大（超过 32MB），不像是 KPM：" + f.getName();
                return res;
            }
        }

        // 1) consistency guard: kptools silently skips legacy kconfig extras, so anything it did
        //    not list would be lost. Refuse rather than eat the user's configuration.
        long hidden = st.declaredExtraSize - st.listedExtraSize;
        if (hidden > 4096) {
            res.error = "镜像里有 " + hidden + " 字节无法识别的嵌入项（通常是 legacy kconfig），"
                    + "重打包会丢失它们，已中止。请改用 APatch/FolkPatch 操作此镜像。";
            return res;
        }

        // 2) preserved extras
        List<Extra> keep = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Extra e : st.extras) {
            if (remove.contains(e.name)) continue;
            keep.add(e);
        }
        for (String n : remove) {
            boolean found = false;
            for (Extra e : st.extras) if (e.name.equals(n)) { found = true; break; }
            if (!found) missing.add(n);
        }
        if (!missing.isEmpty()) {
            res.error = "找不到要移除的嵌入项：" + String.join("、", missing);
            return res;
        }

        // 3) duplicate names would make "-E name" bind to the wrong item
        Map<String, Integer> names = new LinkedHashMap<>();
        for (Extra e : keep) names.merge(e.name.isEmpty() ? "" : e.name, 1, Integer::sum);
        List<String> dup = new ArrayList<>();
        for (Map.Entry<String, Integer> en : names.entrySet()) {
            if (en.getValue() > 1) dup.add(en.getKey().isEmpty() ? "(无名)" : en.getKey());
        }
        if (!dup.isEmpty()) {
            res.error = "存在同名的嵌入项：" + String.join("、", dup)
                    + "，无法安全保留，请先在其中一方用 APatch 移除。";
            return res;
        }

        List<String> newNames = new ArrayList<>();
        for (File f : add) {
            KpmInfo info = KpmInfo.read(f);
            if (!info.parsed) {
                res.error = "无法解析 " + f.getName() + " 的 .kpm.info，无法确定它不是有效 KPM";
                return res;
            }
            if (names.containsKey(info.name)) {
                res.error = "要嵌入的模块名「" + info.name + "」与已嵌入项重名，请先移除旧的那个";
                return res;
            }
            names.put(info.name, 1);
            newNames.add(info.name);
        }

        String work = st.workDir;

        // 4) rescue copy first - this is the single most valuable artefact of the whole flow
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        String backup = BACKUP_DIR + "/boot-backup-" + tail(st.device) + "-" + stamp + ".img";
        String bk = "mkdir -p '" + q(BACKUP_DIR) + "'; "
                + "cp '" + q(work) + "/boot.img' '" + q(backup) + "' 2>/dev/null; "
                + "chmod 0644 '" + q(backup) + "' 2>/dev/null; echo BACKUP_RC=$?";
        KpmShell.Result b = KpmShell.exec(bk, 180000);
        res.log.append("$ cp boot.img -> ").append(backup).append(' ').append(b.out.trim()).append('\n');
        if (b.out.contains("BACKUP_RC=0")) res.backup = backup;

        // 5) stage the new KPM files inside the work dir (root can always read them there)
        StringBuilder args = new StringBuilder();
        int i = 0;
        for (File f : add) {
            String dest = work + "/add" + i + ".kpm";
            String err = KpmShell.writePrivileged(f, dest);
            if (err != null) {
                res.error = "暂存模块失败：" + err;
                return res;
            }
            args.append(" -M '").append(sq(dest)).append("' -T kpm");
            i++;
        }
        // Existing items: kptools pulls their payload out of the input image by name.
        for (Extra e : keep) {
            args.append(" -E '").append(sq(e.name)).append("'");
            args.append(" -T '").append(sq(typeOf(e))).append("'");
            if (!e.event.isEmpty()) args.append(" -V '").append(sq(e.event)).append("'");
            if (!e.args.isEmpty()) args.append(" -A '").append(sq(e.args)).append("'");
        }

        // 6) re-patch. The superkey we pass is a placeholder - step 7 restores the real one.
        String cmd = "cd '" + q(work) + "' && mv -f kernel kernel.ori && '"
                + q(st.kptools) + "' -p -i kernel.ori -S violetbox -k kpimg -o kernel"
                + args + " 2>&1; echo PATCH_RC=$?";
        KpmShell.Result p = KpmShell.exec(cmd, 300000);
        res.log.append("$ kptools -p ...\n").append(p.out.trim()).append('\n');
        if (!p.out.contains("PATCH_RC=0")) {
            res.error = "重新打补丁失败，未做任何刷写。详情见日志。";
            return res;
        }

        // 7) put the original superkey material back
        String keyErr = restoreKeyMaterial(st, work + "/kernel");
        if (keyErr != null) {
            res.error = keyErr;
            return res;
        }
        res.log.append("# 已回填原始 superkey 字段（").append(st.preset.hasRootKeyHash() ? "hash" : "明文")
                .append(" 模式）\n");

        // 8) list again - the new kernel must still parse and carry exactly the expected extras
        KpmShell.Result v = KpmShell.exec(
                "cd '" + q(work) + "' && '" + q(st.kptools) + "' -i kernel -l 2>&1", 60000);
        List<Extra> after = parseExtras(v.out);
        res.log.append("# 校验 kernel：发现 ").append(after.size()).append(" 个嵌入项（期望 ")
                .append(keep.size() + add.size()).append("）\n");
        if (after.size() != keep.size() + add.size()) {
            res.error = "打补丁后的嵌入项数量不符（实际 " + after.size() + "，期望 "
                    + (keep.size() + add.size()) + "），已中止。";
            return res;
        }

        // 9) repack into a boot image
        String rp = "cd '" + q(work) + "' && '" + q(st.kptools) + "' repack boot.img 2>&1; "
                + "echo REPACK_RC=$?; stat -c 'SIZE=%s' new-boot.img 2>/dev/null";
        KpmShell.Result r2 = KpmShell.exec(rp, 300000);
        res.log.append("$ kptools repack -> ").append(r2.out.trim()).append('\n');
        if (!r2.out.contains("REPACK_RC=0")) {
            res.error = "重新打包 boot 镜像失败，未做任何刷写。";
            return res;
        }
        res.newImage = work + "/new-boot.img";

        long imgSize = KpmShell.fileSize(res.newImage);
        long partSize = KpmShell.deviceSize(st.device);
        if (imgSize <= 0) {
            res.error = "没有生成 new-boot.img";
            return res;
        }
        if (partSize > 0 && imgSize > partSize) {
            res.error = "新镜像 " + imgSize + " 字节超过分区容量 " + partSize + " 字节，拒绝刷入。"
                    + "镜像已生成，请用 fastboot 手动处理。";
            return res;
        }

        // 10) end-to-end verification: unpack the artefact we are about to flash
        String verify = "mkdir -p '" + q(work) + "/verify'; cd '" + q(work) + "/verify' && "
                + "rm -f kernel* ; cp ../new-boot.img . 2>/dev/null; '"
                + q(st.kptools) + "' unpack new-boot.img 2>&1; echo VERIFY_UNPACK_RC=$?; '"
                + q(st.kptools) + "' -i kernel -l 2>&1";
        KpmShell.Result v2 = KpmShell.exec(verify, 300000);
        res.log.append("$ 端到端校验 new-boot.img\n").append(v2.out.trim()).append('\n');
        if (!v2.out.contains("VERIFY_UNPACK_RC=0")) {
            res.error = "生成的 boot 镜像无法解包，拒绝刷入。";
            return res;
        }
        List<Extra> verifyList = parseExtras(v2.out);
        if (verifyList.size() != keep.size() + add.size()) {
            res.error = "生成的 boot 镜像缺少嵌入项（实际 " + verifyList.size() + "），拒绝刷入。";
            return res;
        }
        InputStream vin = KpmShell.openRead(work + "/verify/kernel");
        boolean keyOk = false;
        if (vin != null) {
            try {
                KpmPreset.Info vi = KpmPreset.scan(vin, null);
                keyOk = vi != null && java.util.Arrays.equals(vi.rootSuperkey, st.preset.rootSuperkey)
                        && java.util.Arrays.equals(vi.superkey, st.preset.superkey);
            } catch (Exception ignored) {
            } finally {
                close(vin);
            }
        }
        if (!keyOk) {
            res.error = "生成的镜像里 superkey 与原来不一致，拒绝刷入（ROOT 权限可能失效）。";
            return res;
        }
        res.log.append("# 端到端校验通过：嵌入项齐全，superkey 一致\n");

        if (!flash) {
            res.ok = true;
            return res;
        }

        // 11) flash
        List<String> targets = new ArrayList<>();
        targets.add(st.device);
        if (bothSlots) {
            for (String d : KpmShell.candidateBootDevices()) {
                if (!d.equals(st.device) && isSlotSibling(d, st.device)) targets.add(d);
            }
        }
        for (String dev : targets) {
            long ps = KpmShell.deviceSize(dev);
            if (ps > 0 && imgSize > ps) {
                res.error = "跳过 " + dev + "：镜像超出该分区容量";
                continue;
            }
            String dd = "dd if='" + q(res.newImage) + "' of='" + q(dev) + "' bs=4096 2>/dev/null; "
                    + "echo FLASH_RC=$?; sync";
            KpmShell.Result fr = KpmShell.exec(dd, 300000);
            res.log.append("$ dd -> ").append(dev).append(' ').append(fr.out.trim()).append('\n');
            if (fr.out.contains("FLASH_RC=0")) res.flashedTo.add(dev);
        }
        if (res.flashedTo.isEmpty()) {
            res.error = "刷入失败或全部被跳过，详见日志。原始备份：" + res.backup;
            return res;
        }
        res.ok = true;
        return res;
    }

    // ------------------------------------------------------- backup / restore

    /** Copies the current boot partition to {@link #BACKUP_DIR} without touching anything. */
    public static String backupBoot(String device) {
        String dev = device;
        if (dev == null || dev.isEmpty()) {
            List<String> devs = KpmShell.candidateBootDevices();
            if (devs.isEmpty()) return null;
            dev = devs.get(0);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        String out = BACKUP_DIR + "/boot-backup-" + tail(dev) + "-" + stamp + ".img";
        String s = "mkdir -p '" + q(BACKUP_DIR) + "'; "
                + "dd if='" + q(dev) + "' of='" + q(out) + "' bs=4096 2>/dev/null; echo RC=$?; "
                + "chmod 0644 '" + q(out) + "' 2>/dev/null";
        KpmShell.Result r = KpmShell.exec(s, 300000);
        return r.out.contains("RC=0") ? out : null;
    }

    /** Boot image backups in {@link #BACKUP_DIR}, newest first. */
    public static List<String> backups() {
        List<String> out = new ArrayList<>();
        KpmShell.Result r = KpmShell.exec(
                "ls -t '" + q(BACKUP_DIR) + "'/boot-backup-*.img 2>/dev/null", 15000);
        for (String line : r.out.split("\n")) {
            String s = line.trim();
            if (s.startsWith("/")) out.add(s);
        }
        return out;
    }

    /** Writes a backup image back to a boot partition. Caller must have confirmed with the user. */
    public static String restore(String imagePath, String device) {
        long img = KpmShell.fileSize(imagePath);
        if (img <= 0) return "备份文件不可读";
        long part = KpmShell.deviceSize(device);
        if (part > 0 && img > part) return "备份比分区大，拒绝刷入";
        String s = "dd if='" + q(imagePath) + "' of='" + q(device) + "' bs=4096 2>/dev/null; "
                + "echo RC=$?; sync";
        KpmShell.Result r = KpmShell.exec(s, 300000);
        return r.out.contains("RC=0") ? null : "恢复失败：" + r.out.trim();
    }

    // -------------------------------------------------------------- helpers

    /** Restores the original superkey fields into a freshly patched kernel file. */
    private static String restoreKeyMaterial(Inspect st, String kernelPath) {
        if (st.preset == null) return "缺少原始 preset 信息";
        InputStream in = KpmShell.openRead(kernelPath);
        KpmPreset.Info cur = null;
        try {
            if (in == null) return "无法读取新内核";
            cur = KpmPreset.scan(in, null);
        } catch (Exception e) {
            return "解析新内核失败：" + e.getMessage();
        } finally {
            close(in);
        }
        if (cur == null) return "新内核里找不到 KernelPatch 头，已中止（未刷入）";

        long off = cur.offset + KpmPreset.KP_HEADER_OFFSET + KpmPreset.ROOT_SUPERKEY_OFFSET;
        long offPlain = cur.offset + KpmPreset.KP_HEADER_OFFSET + KpmPreset.SUPERKEY_OFFSET;
        String err = KpmShell.writePrivileged(st.preset.rootSuperkey, st.workDir + "/rootkey.bin");
        if (err != null) return "暂存 key 失败：" + err;
        err = KpmShell.writePrivileged(st.preset.superkey, st.workDir + "/plainkey.bin");
        if (err != null) return "暂存 key 失败：" + err;

        String s = "cd '" + q(st.workDir) + "' && "
                + "dd if=plainkey.bin of=kernel bs=1 seek=" + offPlain
                + " count=" + KpmPreset.SUPERKEY_LEN + " conv=notrunc 2>/dev/null; echo PK=$?; "
                + "dd if=rootkey.bin of=kernel bs=1 seek=" + off
                + " count=" + KpmPreset.ROOT_SUPERKEY_LEN + " conv=notrunc 2>/dev/null; echo RK=$?";
        KpmShell.Result r = KpmShell.exec(s, 120000);
        if (!r.out.contains("PK=0") || !r.out.contains("RK=0")) {
            return "回填 superkey 失败：" + r.out.trim() + "（未刷入）";
        }
        // read it back and compare before we trust it
        InputStream chk = KpmShell.openRead(kernelPath);
        try {
            KpmPreset.Info back = chk == null ? null : KpmPreset.scan(chk, null);
            if (back == null) return "回填后无法解析内核（未刷入）";
            if (!java.util.Arrays.equals(back.rootSuperkey, st.preset.rootSuperkey)
                    || !java.util.Arrays.equals(back.superkey, st.preset.superkey)) {
                return "回填校验失败，superkey 不一致（未刷入）";
            }
        } catch (Exception e) {
            return "回填校验异常：" + e.getMessage();
        } finally {
            close(chk);
        }
        return null;
    }

    /** kptools only accepts the type names it knows; anything else becomes "none". */
    private static String typeOf(Extra e) {
        String t = e.type;
        if (t == null) return "kpm";
        switch (t) {
            case "kpm":
            case "shell":
            case "exec":
            case "raw":
            case "android_rc":
                return t;
            default:
                return "none";
        }
    }

    /** True when two by-name paths are the same partition in different slots. */
    private static boolean isSlotSibling(String a, String b) {
        return stripSlot(a).equals(stripSlot(b)) && !a.equals(b);
    }

    private static String stripSlot(String dev) {
        String s = dev.substring(dev.lastIndexOf('/') + 1);
        for (String suf : new String[]{"_a", "_b", "_c"}) {
            if (s.endsWith(suf)) return s.substring(0, s.length() - 2);
        }
        return s;
    }

    private static String tail(String path) {
        if (path == null) return "boot";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** Parses {@code kptools -i kernel -l} output. */
    static List<Extra> parseExtras(String raw) {
        List<Extra> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String section = "";
        Extra cur = null;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("[") && s.endsWith("]")) {
                String head = s.substring(1, s.length() - 1);
                if (head.equals("extra") || head.startsWith("extra ")) {
                    section = "extra";
                    cur = new Extra();
                    out.add(cur);
                } else {
                    section = head;
                    cur = null;
                }
                continue;
            }
            if (!section.equals("extra") || cur == null) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String k = s.substring(0, eq).trim();
            String v = s.substring(eq + 1);
            switch (k) {
                case "index":
                    cur.index = asInt(v, 0);
                    break;
                case "type":
                    cur.type = v;
                    break;
                case "name":
                    cur.name = v;
                    break;
                case "event":
                    cur.event = v;
                    break;
                case "args":
                    cur.args = v;
                    break;
                case "priority":
                    cur.priority = asInt(v, 0);
                    break;
                case "args_size":
                    cur.argsSize = hexOrInt(v);
                    break;
                case "con_size":
                    cur.conSize = hexOrInt(v);
                    break;
                case "version":
                    cur.version = v;
                    break;
                case "author":
                    cur.author = v;
                    break;
                case "description":
                    cur.description = v;
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private static int asInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long hexOrInt(String v) {
        String s = v.trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Escapes a path for single-quoted use in /system/bin/sh. */
    static String q(String s) {
        return KpmShell.q(s);
    }

    /** Escapes a value that will be placed inside single quotes in a shell word. */
    static String sq(String s) {
        return KpmShell.q(s);
    }
}
