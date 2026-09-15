package com.violet.box.kpm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Locates the KernelPatch core image ("kpimg") inside an already patched kernel image, and reads
 * the little bit of it we actually need in order to re-patch that same image.
 *
 * <p>This is the missing half of {@link KpmEmbedded}: the extras chain only tells us <em>what</em>
 * was embedded, but re-running kptools also needs the kpimg blob itself ({@code -k}) plus the
 * superkey material that currently authorises root. Both are already inside the user's own boot
 * image, so nothing has to be shipped or downloaded.
 *
 * <p>Layout of a patched kernel (KernelPatch tools/patch.c: {@code patch_update_img_buf}):
 *
 * <pre>
 *   0                     .. ori_kimg_len   the original kernel image (possibly compressed)
 *   align_ceil(kimg,4096) .. +kpimg_size    the kpimg blob, verbatim
 *   +extras               ..                the 128-byte "kpe" item chain (see KpmEmbedded)
 * </pre>
 *
 * The kpimg blob starts with {@link #KP_HEADER_SIZE} bytes of {@code setup_header_t}
 * (kernel/include/preset.h):
 *
 * <pre>
 *   char     magic[8];        // +0   "KP1158\0\0"  (KP_MAGIC padded to MAGIC_LEN)
 *   version_t kp_version;     // +8   { _, patch, minor, major }
 *   uint32_t _;               // +12
 *   config_t config_flags;    // +16
 *   char     compile_time[24];// +24
 * </pre>
 *
 * followed by {@code setup_preset_t} at +64, whose first fields are all little-endian int64:
 *
 * <pre>
 *   +8   kimg_size       +16 kpimg_size    +56 extra_size
 *   +160 header_backup   +168 superkey[64] +232 root_superkey[32]
 * </pre>
 *
 * Two details make the implementation safe and are mirrored from kptools' own
 * {@code find_patched_preset()}:
 *
 * <ol>
 *   <li>the magic offset must equal {@code align_ceil(kimg_size, 4096)};</li>
 *   <li>the 8 bytes of {@code header_backup} must look like an arm64 kernel primary entry.</li>
 * </ol>
 *
 * Without both checks a random "KP1158" string inside a compressed kernel would happily be
 * accepted.
 */
public final class KpmPreset {

    /** kptools searches the 8 bytes as {@code char magic[8] = "KP1158"}, i.e. NUL padded. */
    private static final byte[] MAGIC = {'K', 'P', '1', '1', '5', '8', 0, 0};

    private static final int KP_HEADER_SIZE = 0x40;     // sizeof(setup_header_t)
    private static final int SETUP_KIMG_SIZE = 0x8;
    private static final int SETUP_KPIMG_SIZE = 0x10;
    private static final int SETUP_EXTRA_SIZE = 0x38;
    private static final int SETUP_HEADER_BACKUP = 0xa0;
    private static final int SETUP_SUPERKEY = 0xa8;     // SUPER_KEY_LEN = 64
    private static final int SETUP_ROOT_SUPERKEY = 0xe8; // then ROOT_SUPER_KEY_HASH_LEN = 32

    private static final int SETUP_END = SETUP_ROOT_SUPERKEY + 32;

    /** sizeof(setup_header_t) - the offset of setup_preset_t inside the kpimg blob. */
    public static final int KP_HEADER_OFFSET = 0x40;
    /** Offset of the plaintext superkey field inside setup_preset_t. */
    public static final int SUPERKEY_OFFSET = 0xa8;
    /** Offset of the SHA256 root-superkey field inside setup_preset_t. */
    public static final int ROOT_SUPERKEY_OFFSET = 0xe8;

    public static final int SUPERKEY_LEN = 64;
    public static final int ROOT_SUPERKEY_LEN = 32;
    /** kpimg is placed on a 4K boundary right after the original kernel image. */
    public static final int ALIGN = 4096;

    private static final int WINDOW = 1 << 20;
    /** Never believe a kpimg that claims to be bigger than this. */
    private static final long MAX_KPIMG = 64L * 1024 * 1024;

    private KpmPreset() {
    }

    /** Everything we could read out of one patched kernel image. */
    public static final class Info {
        /** Absolute offset of the magic inside the streamed image. */
        public long offset;
        /** {@code (major<<16)|(minor<<8)|patch}, e.g. 0x0b04. */
        public int version;
        /** kpimg build stamp string. */
        public String compileTime = "";
        public long kimgSize;
        public long kpimgSize;
        public long extraSize;
        /** Plaintext superkey field (-s mode); all zero when the image uses root-key hash mode. */
        public final byte[] superkey = new byte[SUPERKEY_LEN];
        /** SHA256 of the superkey (-S mode); all zero when the image uses plaintext mode. */
        public final byte[] rootSuperkey = new byte[ROOT_SUPERKEY_LEN];

        public String versionText() {
            int major = (version >> 16) & 0xff;
            int minor = (version >> 8) & 0xff;
            int patch = version & 0xff;
            return major + "." + minor + "." + patch;
        }

        public String versionShort() {
            return String.format("0x%x", version);
        }

        public boolean hasPlaintextKey() {
            return nonZero(superkey, superkey.length);
        }

        public boolean hasRootKeyHash() {
            return nonZero(rootSuperkey, 8);
        }

        private static boolean nonZero(byte[] b, int n) {
            for (int i = 0; i < n; i++) if (b[i] != 0) return true;
            return false;
        }
    }

    /**
     * Streams a patched kernel image, finds the kpimg, and optionally copies the whole blob into
     * {@code kpimgSink}. Returns null when the image carries no KernelPatch patch at all.
     *
     * <p>The returned blob is byte-identical to the kpimg file that was originally used:
     * kptools stores it with {@code read_file_align(..., 0x10)} and records the padded length in
     * {@code setup.kpimg_size}, so carving exactly {@code kpimgSize} bytes reproduces it.
     */
    public static Info scan(InputStream in, OutputStream kpimgSink) throws IOException {
        byte[] buf = new byte[WINDOW];
        int len = 0;         // valid bytes in buf
        long base = 0;       // absolute offset of buf[0]
        int search = 0;      // index from which MAGIC has not been tested yet
        boolean eof = false;

        while (true) {
            if (!eof && len < buf.length) {
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) eof = true;
                else len += n;
            }
            if (eof && len < MAGIC.length) return null;
            if (len < MAGIC.length) continue;

            int found = indexOf(buf, search, len, MAGIC);
            if (found < 0) {
                if (eof) return null;
                // drop everything except the last MAGIC.length-1 bytes (a magic may straddle fills)
                int keep = Math.min(len, MAGIC.length - 1);
                base += len - keep;
                System.arraycopy(buf, len - keep, buf, 0, keep);
                len = keep;
                search = 0;
                continue;
            }

            // need MAGIC + header + first few setup fields up to root_superkey
            long abs = base + found;
            int need = KP_HEADER_SIZE + SETUP_END;
            if (found + need > len) {
                if (eof) return null;
                if (found > 0) {
                    base += found;
                    len -= found;
                    System.arraycopy(buf, found, buf, 0, len);
                    search = 0;
                }
                continue; // refill
            }

            Info info = parse(buf, found, abs);
            if (info != null) {
                if (kpimgSink != null) carve(in, buf, found, len, info.kpimgSize, kpimgSink);
                info.offset = abs;
                return info;
            }
            search = found + 1;
        }
    }

    // --------------------------------------------------------------- internals

    /** Returns an Info when the candidate looks like a real kpimg header, else null. */
    private static Info parse(byte[] b, int off, long abs) {
        long kimgSize = i64(b, off + KP_HEADER_SIZE + SETUP_KIMG_SIZE);
        long kpimgSize = i64(b, off + KP_HEADER_SIZE + SETUP_KPIMG_SIZE);
        long extraSize = i64(b, off + KP_HEADER_SIZE + SETUP_EXTRA_SIZE);

        // kptools reads kimg_size through an int32 cast; do the same.
        int kimg32 = (int) kimgSize;
        if (kimg32 <= 0) return null;
        long aligned = ((kimg32 + (long) (ALIGN - 1)) / ALIGN) * ALIGN;
        if (aligned != abs) return null;

        if (kpimgSize <= 0 || kpimgSize > MAX_KPIMG) return null;
        if (extraSize < 0) return null;

        if (!validHeaderBackup(b, off + KP_HEADER_SIZE + SETUP_HEADER_BACKUP)) return null;

        Info info = new Info();
        info.kimgSize = kimgSize;
        info.kpimgSize = kpimgSize;
        info.extraSize = extraSize;
        int major = b[off + 11] & 0xff;
        int minor = b[off + 10] & 0xff;
        int patch = b[off + 9] & 0xff;
        info.version = (major << 16) | (minor << 8) | patch;
        info.compileTime = cstr(b, off + 24, 24);
        System.arraycopy(b, off + KP_HEADER_SIZE + SETUP_SUPERKEY, info.superkey, 0, SUPERKEY_LEN);
        System.arraycopy(b, off + KP_HEADER_SIZE + SETUP_ROOT_SUPERKEY,
                info.rootSuperkey, 0, ROOT_SUPERKEY_LEN);
        return info;
    }

    /** Mirrors kptools' {@code header_backup_has_valid_primary_entry()}. */
    private static boolean validHeaderBackup(byte[] b, int off) {
        long v = u32(b, off);
        if ((v & 0xFC000000L) == 0x14000000L) return true;
        if (b[off] == 'M' && b[off + 1] == 'Z') {
            v = u32(b, off + 4);
            return (v & 0xFC000000L) == 0x14000000L;
        }
        return false;
    }

    /** Copies kpimgSize bytes starting at {@code rel} into sink, pulling from the stream as needed. */
    private static void carve(InputStream in, byte[] buf, int rel, int len, long kpimgSize,
                              OutputStream sink) throws IOException {
        int first = (int) Math.min(kpimgSize, len - rel);
        sink.write(buf, rel, first);
        long remaining = kpimgSize - first;
        byte[] chunk = new byte[1 << 16];
        while (remaining > 0) {
            int n = in.read(chunk, 0, (int) Math.min(remaining, chunk.length));
            if (n < 0) throw new java.io.EOFException("image truncated inside kpimg");
            sink.write(chunk, 0, n);
            remaining -= n;
        }
        sink.flush();
    }

    private static int indexOf(byte[] b, int from, int len, byte[] pat) {
        outer:
        for (int i = from; i + pat.length <= len; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (b[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    private static long i64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    private static String cstr(byte[] b, int off, int max) {
        int end = off;
        int limit = off + max;
        while (end < limit && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }
}
