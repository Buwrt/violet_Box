package com.violet.box.kpm;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读的「已嵌入 KPM」识别器 —— 只服务于「模块备份」。
 *
 * 一条 KernelPatch 打过补丁的内核镜像，由 {@code patch_update_img_buf()} 摆成这样：
 *
 * <pre>
 *   [原始 kernel][kpimg][128 字节一条的 "kpe" extra 链]
 * </pre>
 *
 * 用 APatch 的「嵌入」方式刷进去的 KPM，就作为 extra 项挂在这条链上，磁盘上并没有独立的
 * .kpm 文件。所以备份它们需要的不是复制文件，而是把整条链读出来、把 payload ELF 原样抠出来。
 *
 * <p>本类做的正是这件事：dump 分区 → {@code kptools unpack} → 读 kpe 头链 → 抠 ELF。
 * 全程只读，不写回任何分区。V11 里那条会重写 boot 镜像的 {@code patch()} 流程（kptools -p →
 * repack → dd 回分区）已经彻底删除，不再存在于本工程中。
 */
public final class KpmEmbedTool {

    /** Root 侧的工作目录，每次操作前重建。 */
    public static final String WORK = "/data/local/tmp/violetbox_embed";

    private KpmEmbedTool() {
    }

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

}
