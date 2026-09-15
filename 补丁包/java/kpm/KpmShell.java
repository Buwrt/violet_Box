package com.violet.box.kpm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Root-shell access to the KernelPatch Module store.
 *
 * Everything here mirrors what APatch itself does, so both apps stay in agreement about where a
 * KPM lives. The layout (from APatch's APatchApp.kt / KPModuleViewModel.kt) is:
 *
 * <pre>
 *   /data/adb/ap/                      APATCH_FOLDER
 *   /data/adb/ap/bin/kptools           metadata dumper (kptools -l -M file.kpm)
 *   /data/adb/ap/kpm/&lt;id&gt;/&lt;id&gt;.kpm     an *installed* KPM; loaded by the boot-time loader
 *   /data/adb/ap/kpm/&lt;id&gt;/disable      presence of this file disables the module
 * </pre>
 *
 * Installing is therefore pure file placement - no kernel call, no superkey - and takes effect
 * after a reboot. Runtime load/unload is a completely different story: it goes through the
 * KernelPatch supercall (syscall 45, SUPERCALL_KPM_LOAD) which requires the superkey, something
 * only APatch holds. We deliberately do not attempt it.
 */
public final class KpmShell {

    public static final String APATCH_FOLDER = "/data/adb/ap/";
    public static final String KPMS_DIR = APATCH_FOLDER + "kpm/";
    /** Community-convention directory used by many KPM READMEs; not managed by APatch. */
    public static final String KPM_LEGACY_DIR = "/data/adb/kpm/";
    public static final String KPTOOLS = APATCH_FOLDER + "bin/kptools";
    /** World-readable scratch area used to hand root-owned .kpm files to our ELF parser. */
    private static final String SCRATCH = "/data/local/tmp/violetbox_kpm";

    private KpmShell() {
    }

    public static final class Result {
        public final int code;
        public final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    /** Runs a script through "su -c". Always returns; never throws. */
    public static Result exec(String script) {
        return exec(script, 20000L);
    }

    public static Result exec(String script, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            final Process proc = p;
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException ignored) {
                    return;
                }
                try {
                    proc.destroy();
                } catch (Exception ignored) {
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            int code = proc.waitFor();
            return new Result(code, sb.toString());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e.getMessage()));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ environment

    public static boolean haveRoot() {
        return exec("id -u", 8000).out.trim().equals("0");
    }

    public static boolean isApatch() {
        return exec("[ -d '" + APATCH_FOLDER + "' ] && [ -f /data/adb/apd ]").ok();
    }

    public static String apatchVersion() {
        Result r = exec("cat '" + APATCH_FOLDER + "version' 2>/dev/null");
        return r.ok() ? r.out.trim() : "";
    }

    public static boolean kptoolsAvailable() {
        return exec("[ -x '" + KPTOOLS + "' ]").ok();
    }

    // -------------------------------------------------------------- inventory

    /** One installed KPM: its id plus whether APatch currently has it disabled. */
    public static final class Installed {
        public final String id;
        public final boolean disabled;

        Installed(String id, boolean disabled) {
            this.id = id;
            this.disabled = disabled;
        }

        public String kpmPath() {
            return KPMS_DIR + id + "/" + id + ".kpm";
        }
    }

    public static List<Installed> listInstalled() {
        List<Installed> out = new ArrayList<>();
        Result r = exec("for d in '" + KPMS_DIR + "'*; do "
                + "[ -d \"$d\" ] || continue; "
                + "id=\"${d##*/}\"; "
                + "if [ -e \"$d/disable\" ]; then echo \"$id|1\"; else echo \"$id|0\"; fi; "
                + "done 2>/dev/null");
        if (!r.ok() && r.out.trim().isEmpty()) return out;
        for (String line : r.out.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            int bar = s.indexOf('|');
            if (bar <= 0) continue;
            out.add(new Installed(s.substring(0, bar), s.substring(bar + 1).equals("1")));
        }
        return out;
    }

    /** Copies a root-owned .kpm into a world-readable scratch dir so Java can parse it.
     *  Returns the scratch path, or null when the copy failed. */
    public static String exposeForRead(String kpmPath, String id) {
        String safe = KpmInfo.safeId(id);
        exec("mkdir -p '" + SCRATCH + "' && rm -f '" + SCRATCH + "/" + safe + ".kpm'");
        Result r = exec("cp -f '" + q(kpmPath) + "' '" + SCRATCH + "/" + safe + ".kpm' "
                + "&& chmod 0644 '" + SCRATCH + "/" + safe + ".kpm'");
        return r.ok() ? SCRATCH + "/" + safe + ".kpm" : null;
    }

    /**
     * Reads a KPM's metadata. Tries a direct read first (works for /sdcard files), otherwise
     * copies it out of the root-only store, and finally falls back to APatch's kptools.
     */
    public static KpmInfo readInfo(String kpmPath, String fallbackId) {
        java.io.File f = new java.io.File(kpmPath);
        if (f.canRead()) {
            KpmInfo info = KpmInfo.read(f);
            if (info.parsed) return info;
        }
        String scratch = exposeForRead(kpmPath, fallbackId);
        if (scratch != null) {
            KpmInfo info = KpmInfo.read(new java.io.File(scratch));
            if (info.parsed) {
                // Report the real store path, not the scratch copy.
                return new KpmInfo(kpmPath, info.name, info.version, info.license, info.author,
                        info.description, info.size, true);
            }
        }
        if (kptoolsAvailable()) {
            Result r = exec("'" + KPTOOLS + "' -l -M '" + q(kpmPath) + "'");
            if (r.ok()) {
                java.util.Map<String, String> kv = parseKptoolsIni(r.out);
                if (!kv.isEmpty()) {
                    return new KpmInfo(kpmPath, kv.get("name"), kv.get("version"), kv.get("license"),
                            kv.get("author"), kv.get("description"), 0L, true);
                }
            }
        }
        return new KpmInfo(kpmPath, null, null, null, null, null, 0L, false);
    }

    /** kptools -l -M prints an ini-ish blob with a [kpm] section. */
    static java.util.Map<String, String> parseKptoolsIni(String raw) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        boolean inSection = false;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) continue;
            if (s.startsWith("[")) {
                inSection = s.equalsIgnoreCase("[kpm]");
                continue;
            }
            if (!inSection) continue;
            int eq = s.indexOf('=');
            if (eq > 0) {
                String k = s.substring(0, eq).trim();
                if (!k.isEmpty() && !out.containsKey(k)) out.put(k, s.substring(eq + 1).trim());
            }
        }
        return out;
    }

    // ------------------------------------------------------- embedded (boot image)

    /** Embedded-KPM scan result for one boot image partition. */
    public static final class BootScan {
        /** Block device that was scanned, e.g. /dev/block/by-name/boot_a. */
        public final String partition;
        /** One KpmInfo per embedded KPM; info.path points at the carved scratch copy. */
        public final List<KpmInfo> items;

        BootScan(String partition, List<KpmInfo> items) {
            this.partition = partition;
            this.items = items;
        }
    }

    /**
     * Finds the patched boot image and carves the KPMs embedded in it (the "Embed" route,
     * shown by APatch as 「已嵌入」) into {@code outDir} (an app-writable cache dir).
     *
     * <p>The partition is streamed through {@code su -c cat} so no permissions beyond root
     * are needed and nothing large is held in memory. Returns null when no candidate
     * partition carries embedded KPMs.
     */
    public static BootScan scanEmbeddedBoot(File outDir) {
        // stale carve output from an earlier scan
        if (outDir.isDirectory()) {
            File[] stale = outDir.listFiles();
            if (stale != null) for (File f : stale) f.delete();
        } else {
            outDir.mkdirs();
        }

        int tried = 0;
        for (String dev : candidateBootDevices()) {
            if (tried >= 4) break;
            Result sz = exec("blockdev --getsize64 '" + q(dev) + "' 2>/dev/null");
            try {
                long size = Long.parseLong(sz.out.trim());
                if (size > 512L * 1024 * 1024) continue; // never cat something absurd
            } catch (Exception ignored) {
            }
            tried++;
            List<KpmEmbedded.Item> items = scanDevice(dev, outDir);
            List<KpmInfo> infos = new ArrayList<>();
            for (KpmEmbedded.Item it : items) {
                if (it.file != null && it.info != null) infos.add(it.info);
            }
            if (!infos.isEmpty()) return new BootScan(dev, infos);
        }
        return null;
    }

    /**
     * Opens a root-owned file for streaming read, exactly like {@link #scanDevice} does for block
     * devices. The returned stream must be closed, which also reaps the "su" process.
     */
    public static InputStream openRead(String path) {
        Process p;
        try {
            p = new ProcessBuilder("su", "-c", "cat '" + q(path) + "'").redirectErrorStream(false).start();
        } catch (Exception e) {
            return null;
        }
        final Process proc = p;
        Thread drain = new Thread(() -> {
            try (InputStream e = proc.getErrorStream()) {
                byte[] b = new byte[4096];
                while (e.read(b) >= 0) { /* discard */ }
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();
        return new FilterInputStream(p.getInputStream()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    proc.destroy();
                }
            }
        };
    }

    /**
     * Writes bytes to a root-owned destination by piping them into {@code su -c "cat > file"}.
     * Returns null on success, otherwise a human readable error.
     */
    public static String writePrivileged(byte[] data, String destPath) {
        return writePrivileged(new java.io.ByteArrayInputStream(data), destPath);
    }

    /** Same as {@link #writePrivileged(byte[], String)} for an already existing local file. */
    public static String writePrivileged(File src, String destPath) {
        try (InputStream in = new FileInputStream(src)) {
            return writePrivileged(in, destPath);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    public static String writePrivileged(InputStream data, String destPath) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "cat > '" + q(destPath) + "'")
                    .redirectErrorStream(true).start();
            final Process proc = p;
            StringBuilder err = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (err.length() > 0) err.append('\n');
                        err.append(line);
                    }
                } catch (IOException ignored) {
                }
            });
            drain.setDaemon(true);
            drain.start();
            try (OutputStream os = proc.getOutputStream()) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = data.read(buf)) > 0) os.write(buf, 0, n);
            }
            int code = proc.waitFor();
            if (code != 0) return "写入失败（code=" + code + "）" + (err.length() == 0 ? "" : "：" + err);
            Result check = exec("[ -s '" + q(destPath) + "' ]");
            return check.ok() ? null : "写入后文件为空";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** True when a shell utility exists in the root PATH. */
    public static boolean commandExists(String name) {
        return exec("command -v " + name + " >/dev/null 2>&1").ok();
    }

    /** Human readable size for logs/toasts. */
    public static String sizeOf(String path) {
        Result r = exec("stat -c %s '" + q(path) + "' 2>/dev/null");
        String s = r.out.trim();
        if (s.isEmpty()) return "?";
        try {
            long n = Long.parseLong(s);
            return n >= 1024 * 1024 ? (n / (1024 * 1024)) + " MB" : Math.max(1, n / 1024) + " KB";
        } catch (Exception e) {
            return s;
        }
    }

    /** Block-device candidates for the current boot-related partition, most likely first. */
    public static List<String> candidateBootDevices() {
        String suffix = exec("getprop ro.boot.slot_suffix 2>/dev/null").out.trim();
        List<String> names = new ArrayList<>();
        if (suffix.startsWith("_")) {
            names.add("boot" + suffix);
            names.add("init_boot" + suffix);
        }
        names.add("boot");
        names.add("boot_a");
        names.add("boot_b");
        names.add("init_boot");
        names.add("init_boot_a");
        names.add("init_boot_b");

        StringBuilder script = new StringBuilder();
        for (String n : names) {
            for (String base : new String[]{"/dev/block/by-name", "/dev/block/bootdevice/by-name"}) {
                script.append("p=\"").append(base).append('/').append(n).append("\"; ")
                        .append("if [ -e \"$p\" ]; then echo \"$p\"; fi; ");
            }
        }
        LinkedHashSet<String> devs = new LinkedHashSet<>();
        for (String line : exec(script.toString(), 15000).out.split("\n")) {
            String s = line.trim();
            if (s.startsWith("/dev/")) devs.add(s);
        }
        return new ArrayList<>(devs);
    }

    /** Size of a block device in bytes, or -1 when unknown. */
    public static long deviceSize(String dev) {
        Result r = exec("blockdev --getsize64 '" + q(dev) + "' 2>/dev/null");
        try {
            return Long.parseLong(r.out.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    /** Numeric size of a regular file, or -1. */
    public static long fileSize(String path) {
        Result r = exec("stat -c %s '" + q(path) + "' 2>/dev/null");
        try {
            return Long.parseLong(r.out.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Scans the boot-related partitions until one carries a KernelPatch patch, and returns the
     * kpimg metadata found there. Used to answer "can this device embed KPMs at all?" without
     * touching anything on disk.
     */
    public static PresetProbe probeKernelPatch() {
        int tried = 0;
        for (String dev : candidateBootDevices()) {
            if (tried >= 4) break;
            long size = deviceSize(dev);
            if (size > 512L * 1024 * 1024) continue; // never stream something absurd
            tried++;
            InputStream in = null;
            try {
                in = openRead(dev);
                if (in == null) continue;
                KpmPreset.Info info = KpmPreset.scan(in, null);
                if (info != null) return new PresetProbe(dev, info);
            } catch (Exception ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return null;
    }

    /** Result of {@link #probeKernelPatch} - where the running KernelPatch patch lives. */
    public static final class PresetProbe {
        public final String device;
        public final KpmPreset.Info preset;

        PresetProbe(String device, KpmPreset.Info preset) {
            this.device = device;
            this.preset = preset;
        }
    }

    private static List<KpmEmbedded.Item> scanDevice(String dev, File outDir) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "cat '" + q(dev) + "'").redirectErrorStream(false).start();
            final Process proc = p;
            Thread errDrain = new Thread(() -> {
                try (java.io.InputStream e = proc.getErrorStream()) {
                    byte[] b = new byte[4096];
                    while (e.read(b) >= 0) { /* discard */ }
                } catch (Exception ignored) {
                }
            });
            errDrain.setDaemon(true);
            errDrain.start();
            try (java.io.InputStream in = p.getInputStream()) {
                return KpmEmbedded.scan(in, outDir);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------- operations

    /** Places a .kpm into the store. Returns null on success, otherwise an error string. */
    public static String install(String srcPath, String id) {
        String safe = KpmInfo.safeId(id);
        if (safe.isEmpty()) return "无法从模块名得到合法 id";
        String dir = KPMS_DIR + safe;
        String dest = dir + "/" + safe + ".kpm";
        Result r = exec("mkdir -p '" + dir + "' || exit 1\n"
                + "cp -f '" + q(srcPath) + "' '" + dest + "' || exit 2\n"
                + "chmod 0644 '" + dest + "'\n"
                + "chown 0:0 '" + dest + "' 2>/dev/null\n"
                + "restorecon '" + dest + "' 2>/dev/null\n"
                + "rm -f '" + dir + "/disable' 2>/dev/null\n"
                + "exit 0", 30000);
        if (!r.ok()) return "刷入失败（code=" + r.code + "）" + (r.out.isEmpty() ? "" : "：" + r.out);
        return null;
    }

    public static String setEnabled(String id, boolean enabled) {
        String safe = KpmInfo.safeId(id);
        String flag = KPMS_DIR + safe + "/disable";
        Result r = exec(enabled ? "rm -f '" + flag + "'" : "mkdir -p '" + KPMS_DIR + safe + "' && touch '" + flag + "'");
        return r.ok() ? null : "操作失败：" + r.out;
    }

    public static String uninstall(String id) {
        String safe = KpmInfo.safeId(id);
        Result r = exec("rm -rf '" + KPMS_DIR + safe + "'");
        return r.ok() ? null : "卸载失败：" + r.out;
    }

    /** Escapes a path so it is safe inside single quotes for /system/bin/sh. */
    static String q(String s) {
        return s == null ? "" : s.replace("'", "'\"'\"'");
    }
}
