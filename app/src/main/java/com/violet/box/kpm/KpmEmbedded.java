package com.violet.box.kpm;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Scanner for KPMs that were <em>embedded</em> into the patched kernel image (KernelPatch's
 * "Embed" route, shown by APatch as 「已嵌入」).
 *
 * <p>These KPMs never exist as files under /data/adb/ap/kpm - they live inside the boot (or
 * init_boot) partition right after the kpimg blob. kptools appends them as a chain of fixed
 * 128-byte headers, each followed by its args and payload (kernel/include/preset.h):
 *
 * <pre>
 *   struct patch_extra_item_t {          // little-endian (arm64 Android)
 *       char     magic[4];   // +0   "kpe\0"
 *       int32_t  priority;   // +4
 *       int32_t  args_size;  // +8
 *       int32_t  con_size;   // +12  payload length (for KPM: the whole .kpm ELF)
 *       int32_t  type;       // +16  0=none 1=kpm 2=shell 3=exec 4=raw 5=android_rc 6=legacy-kconfig
 *       char     name[32];   // +20
 *       char     event[32];  // +52
 *       int32_t  flags;      // +84
 *   };                        // 128 bytes total, then args_size bytes, then con_size bytes
 * </pre>
 *
 * <p>The KPM payload is the original relocatable ELF (ET_REL with .kpm.info), so it can be
 * carved out byte-for-byte: parsed here for metadata, exported, or backed up as a zip.
 *
 * <p>The image is read as a stream (the caller pipes {@code su -c cat <block device>}), so
 * nothing large is ever held in memory and no SELinux issue can occur - root reads the
 * partition, we only see the pipe.
 */
public final class KpmEmbedded {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_KPM = 1;

    /** One embedded extra item found in the image. */
    public static final class Item {
        public long headerOffset;
        public int type;
        public String name = "";   // embed name given to kptools -E (may be empty)
        public String event = "";
        public int argsSize;
        public int conSize;
        /** Carved payload file - KPM items only; null for other types. */
        public File file;
        /** Metadata parsed from the carved ELF; unparsed when the blob is not a valid KPM. */
        public KpmInfo info;
    }

    private static final int HEADER_LEN = 128;
    private static final int NAME_LEN = 32;
    private static final int EVENT_LEN = 32;
    /** Sanity caps so a corrupted "kpe" never makes us read gigabytes. */
    private static final int MAX_CON = 32 * 1024 * 1024;
    private static final int MAX_ARGS = 64 * 1024;
    private static final int WINDOW = 1 << 20;

    private KpmEmbedded() {
    }

    /**
     * Streams an image (boot / init_boot partition or a boot.img file) and carves every embedded
     * KPM payload into {@code outDir} as {@code embed_&lt;n&gt;_&lt;name&gt;.kpm}. Returns the items in
     * image order; callers usually keep those with {@code file != null}.
     */
    public static List<Item> scan(InputStream in, File outDir) throws IOException {
        if (!outDir.isDirectory()) outDir.mkdirs();
        List<Item> out = new ArrayList<>();
        byte[] buf = new byte[WINDOW];
        long base = 0;   // absolute image offset of buf[0]
        int len = 0;     // valid bytes in buf
        boolean eof = false;
        int carved = 0;

        while (true) {
            // 1) fill the window
            if (!eof && len < buf.length) {
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) eof = true;
                else len += n;
            }
            if (len < 4) {
                if (eof) break;
                continue; // tiny reads: keep filling
            }

            // 2) locate the next "kpe\0" in the valid window
            int found = -1;
            for (int i = 0; i + 4 <= len; i++) {
                if (buf[i] == 'k' && buf[i + 1] == 'p' && buf[i + 2] == 'e' && buf[i + 3] == 0) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                if (eof) break;
                // keep the last 3 bytes so a magic split across window fills is still seen
                base += len - 3;
                buf[0] = buf[len - 3];
                buf[1] = buf[len - 2];
                buf[2] = buf[len - 1];
                len = 3;
                continue;
            }

            long headerAbs = base + found;

            // 3) need the full 128-byte header in the window
            if (found + HEADER_LEN > len) {
                if (eof) break;
                base += found;
                len -= found;
                System.arraycopy(buf, found, buf, 0, len);
                continue; // refill at top of loop
            }

            // 4) parse the header
            int argsSize = le32(buf, found + 8);
            int conSize = le32(buf, found + 12);
            int type = le32(buf, found + 16);
            String name = cstr(buf, found + 20, NAME_LEN);
            String event = cstr(buf, found + 52, EVENT_LEN);

            boolean shapeOk = argsSize >= 0 && argsSize <= MAX_ARGS
                    && conSize > 0 && conSize <= MAX_CON
                    && type >= TYPE_NONE && type <= 6;
            if (!shapeOk) {
                // false positive: step 4 bytes and keep scanning
                base += found + 4;
                len -= found + 4;
                System.arraycopy(buf, found + 4, buf, 0, len);
                continue;
            }

            long payloadAbs = headerAbs + HEADER_LEN + argsSize;
            long itemEndAbs = payloadAbs + conSize;

            Item item = new Item();
            item.headerOffset = headerAbs;
            item.type = type;
            item.name = name;
            item.event = event;
            item.argsSize = argsSize;
            item.conSize = conSize;

            if (type == TYPE_KPM) {
                // verify the payload really is an ELF before carving anything
                while (payloadAbs + 4 > base + len && !eof) {
                    // compact so the header sits at 0, then pull more stream data
                    base += found;
                    len -= found;
                    System.arraycopy(buf, found, buf, 0, len);
                    found = 0;
                    int n = in.read(buf, len, buf.length - len);
                    if (n < 0) eof = true;
                    else len += n;
                }
                int rel = (int) (payloadAbs - base);
                if (rel >= 0 && rel + 4 <= len
                        && buf[rel] == 0x7f && buf[rel + 1] == 'E' && buf[rel + 2] == 'L' && buf[rel + 3] == 'F') {
                    File f = new File(outDir, "embed_" + carved + "_"
                            + KpmInfo.safeId(name.isEmpty() ? ("kpm" + carved) : name) + ".kpm");
                    carve(in, buf, rel, len, payloadAbs, conSize, f);
                    carved++;
                    item.file = f;
                    KpmInfo info = KpmInfo.read(f);
                    if (!info.parsed) {
                        info = new KpmInfo(f.getAbsolutePath(), name.isEmpty() ? null : name,
                                null, null, null, null, (long) conSize, false);
                    }
                    item.info = info;
                    out.add(item);
                    // next header starts right after the payload; keep any prefetched bytes
                    long nextAbs = payloadAbs + conSize;
                    int drop = (int) (nextAbs - base);
                    if (drop >= len) {
                        base = nextAbs;
                        len = 0;
                    } else {
                        System.arraycopy(buf, drop, buf, 0, len - drop);
                        base = nextAbs;
                        len -= drop;
                    }
                    continue;
                }
                // not an ELF: false positive, step past the magic only
                base += found + 4;
                len -= found + 4;
                System.arraycopy(buf, found + 4, buf, 0, len);
                continue;
            }

            // 5) recognized non-KPM extra (shell/exec/raw/...): record and skip its payload
            out.add(item);
            long drop = itemEndAbs - base;
            if (drop >= len) {
                base = itemEndAbs;
                len = 0;
            } else {
                System.arraycopy(buf, (int) drop, buf, 0, (int) (len - drop));
                base = itemEndAbs;
                len -= (int) drop;
            }
        }
        return out;
    }

    /** Copies conSize payload bytes (partially prefetched in buf[rel..len)) into out. */
    private static void carve(InputStream in, byte[] buf, int rel, int len,
                              long payloadAbs, int conSize, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            int first = (int) Math.min((long) conSize, len - rel);
            os.write(buf, rel, first);
            long remaining = conSize - first;
            byte[] chunk = new byte[1 << 16];
            while (remaining > 0) {
                int n = in.read(chunk, 0, (int) Math.min(remaining, chunk.length));
                if (n < 0) throw new EOFException("image truncated inside KPM payload");
                os.write(chunk, 0, n);
                remaining -= n;
            }
        }
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static String cstr(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }
}
