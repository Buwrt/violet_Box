package com.violet.box.kpm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
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
