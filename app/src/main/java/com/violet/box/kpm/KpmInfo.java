package com.violet.box.kpm;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Metadata of a KernelPatch Module (.kpm).
 *
 * A KPM is a relocatable ELF that carries its metadata in a dedicated section named
 * ".kpm.info". KernelPatch builds that section from the KPM_NAME / KPM_VERSION / KPM_LICENSE /
 * KPM_AUTHOR / KPM_DESCRIPTION macros, each of which emits one NUL-terminated
 * "key=value" string:
 *
 * <pre>
 *   static const char __kpm_info_name[] __attribute__((section(".kpm.info"), aligned(1)))
 *       = "name=" "hello";
 * </pre>
 *
 * so the raw section content is simply {@code "name=hello\0version=1.0\0..."}. That means we can
 * read the metadata without any native code and without depending on kptools being present.
 *
 * Field length limits come from KernelPatch's kernel/include/kpmodule.h:
 * name/version/license/author 32 bytes, description 512 bytes.
 */
public final class KpmInfo {

    public final String path;
    public final String name;
    public final String version;
    public final String license;
    public final String author;
    public final String description;
    public final long size;
    /** True when the ELF really had a .kpm.info section; false means we only know the file name. */
    public final boolean parsed;

    public KpmInfo(String path, String name, String version, String license, String author,
                   String description, long size, boolean parsed) {
        this.path = path;
        this.name = name;
        this.version = version;
        this.license = license;
        this.author = author;
        this.description = description;
        this.size = size;
        this.parsed = parsed;
    }

    /** APatch's own rule (see safeKpmModuleId): the id doubles as the directory name under
     *  /data/adb/ap/kpm/, so it has to survive being used as a path component. */
    public static String safeId(String rawName) {
        String s = rawName == null ? "" : rawName.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 64; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        // trim('.', '_', '-')
        int a = 0;
        int b = out.length();
        while (a < b && isTrimChar(out.charAt(a))) a++;
        while (b > a && isTrimChar(out.charAt(b - 1))) b--;
        out = out.substring(a, b);
        return out.isEmpty() ? "kpm" : out;
    }

    private static boolean isTrimChar(char c) {
        return c == '.' || c == '_' || c == '-';
    }

    /** Best display name: KPM name if we parsed it, otherwise the file name without .kpm. */
    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String f = path == null ? "" : path;
        int slash = f.lastIndexOf('/');
        if (slash >= 0) f = f.substring(slash + 1);
        if (f.toLowerCase(Locale.ROOT).endsWith(".kpm")) f = f.substring(0, f.length() - 4);
        return f.isEmpty() ? "未知模块" : f;
    }

    public String id() {
        return safeId(displayName());
    }

    /** Reads a .kpm file. Never throws; returns an unparsed info when the file is not readable
     *  or is not a KPM we understand. */
    public static KpmInfo read(File file) {
        String path = file == null ? "" : file.getAbsolutePath();
        long size = file == null ? 0L : file.length();
        byte[] data = readAll(file);
        if (data != null) {
            Map<String, String> kv = parseElfKpmInfo(data);
            if (kv != null && !kv.isEmpty()) {
                return new KpmInfo(path,
                        kv.get("name"), kv.get("version"), kv.get("license"),
                        kv.get("author"), kv.get("description"), size, true);
            }
        }
        KpmInfo fallback = new KpmInfo(path, null, null, null, null, null, size, false);
        return fallback;
    }

    // ------------------------------------------------------------------ ELF

    private static byte[] readAll(File file) {
        if (file == null || !file.isFile()) return null;
        long len = file.length();
        if (len <= 0 || len > 64L * 1024 * 1024) return null;
        byte[] buf = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    /** Locates ".kpm.info" and returns its key/value pairs, or null when not an ELF. */
    static Map<String, String> parseElfKpmInfo(byte[] b) {
        if (b.length < 64) return null;
        if (b[0] != 0x7f || b[1] != 'E' || b[2] != 'L' || b[3] != 'F') return null;
        boolean elf64 = b[4] == 2;
        boolean little = b[5] == 1;
        ByteOrder order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

        long shoff;
        int shentsize;
        int shnum;
        int shstrndx;
        int shNameOff, shTypeOff, shOffsetOff, shSizeOff;
        if (elf64) {
            shoff = u64(b, 40, order);
            shentsize = u16(b, 58, order);
            shnum = u16(b, 60, order);
            shstrndx = u16(b, 62, order);
            shNameOff = 0; shTypeOff = 4; shOffsetOff = 24; shSizeOff = 32;
        } else {
            shoff = u32(b, 32, order);
            shentsize = u16(b, 46, order);
            shnum = u16(b, 48, order);
            shstrndx = u16(b, 50, order);
            shNameOff = 0; shTypeOff = 4; shOffsetOff = 16; shSizeOff = 20;
        }
        // ELF uses section index 0xff00/0xffff sentinel values for "too many sections";
        // treat anything that cannot possibly fit in the file as "no sections".
        if (shentsize <= 0 || shnum <= 0 || shoff <= 0
                || shoff + (long) shentsize * shnum > b.length) {
            return null;
        }

        long strOff = -1;
        long strSize = 0;
        if (shstrndx > 0 && shstrndx < shnum) {
            long h = shoff + (long) shstrndx * shentsize;
            strOff = elf64 ? u64(b, (int) (h + shOffsetOff), order) : u32(b, (int) (h + shOffsetOff), order);
            strSize = elf64 ? u64(b, (int) (h + shSizeOff), order) : u32(b, (int) (h + shSizeOff), order);
        }
        if (strOff < 0 || strOff > b.length) strOff = -1;

        for (int i = 0; i < shnum; i++) {
            long h = shoff + (long) i * shentsize;
            int nameIdx = u32(b, (int) (h + shNameOff), order);
            String secName = null;
            if (strOff >= 0 && strSize > 0 && nameIdx >= 0 && nameIdx < strSize) {
                long p = strOff + nameIdx;
                if (p >= 0 && p < b.length) secName = cstring(b, (int) p);
            }
            if (!".kpm.info".equals(secName)) continue;

            long off = elf64 ? u64(b, (int) (h + shOffsetOff), order) : u32(b, (int) (h + shOffsetOff), order);
            long size = elf64 ? u64(b, (int) (h + shSizeOff), order) : u32(b, (int) (h + shSizeOff), order);
            if (off < 0 || size <= 0 || off + size > b.length) return null;
            byte[] section = new byte[(int) size];
            System.arraycopy(b, (int) off, section, 0, (int) size);
            return parseKeyValueBlob(section);
        }
        return null;
    }

    /** Splits the NUL-separated "key=value" blob. */
    static Map<String, String> parseKeyValueBlob(byte[] data) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length) {
            int end = i;
            while (end < data.length && data[end] != 0) end++;
            if (end > i) {
                String s = new String(data, i, end - i, StandardCharsets.UTF_8);
                int eq = s.indexOf('=');
                if (eq > 0) {
                    String k = s.substring(0, eq).trim();
                    String v = s.substring(eq + 1).trim();
                    if (!k.isEmpty() && !out.containsKey(k)) out.put(k, v);
                }
            }
            i = end + 1;
        }
        return out;
    }

    private static String cstring(byte[] b, int off) {
        int end = off;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static int u16(byte[] b, int off, ByteOrder o) {
        if (off + 2 > b.length) return 0;
        return ByteBuffer.wrap(b, off, 2).order(o).getShort() & 0xFFFF;
    }

    private static int u32(byte[] b, int off, ByteOrder o) {
        if (off < 0 || off + 4 > b.length) return -1;
        return ByteBuffer.wrap(b, off, 4).order(o).getInt();
    }

    private static long u64(byte[] b, int off, ByteOrder o) {
        if (off < 0 || off + 8 > b.length) return -1;
        return ByteBuffer.wrap(b, off, 8).order(o).getLong();
    }
}
