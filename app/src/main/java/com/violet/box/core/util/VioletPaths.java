package com.violet.box.core.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * 紫罗兰Box 的落盘路径中心。
 *
 * <p>用户要求：下载的东西、备份的东西，一律放在
 * {@code /storage/emulated/0/Download/VioletBox} 下面，方便在文件管理器里直接找到。
 *
 * <p>难点：targetSdk 36 强制分区存储，应用进程直接写公共目录会被 FUSE 拒绝。
 * 所以策略是三级递进：
 * <ol>
 *     <li>公共目录本身可写（已授予「所有文件访问权限」或 Android 10 以下）→ 直接写；</li>
 *     <li>不可写但设备已 root → 用 {@code su} 建目录并 {@code chmod 777}，再试一次；</li>
 *     <li>仍不可写 → 写应用私有目录，下载完成后再走 {@link #publishToPublic} 用 root 复制过去。</li>
 * </ol>
 */
public final class VioletPaths {

    /** 用户指定的公共根目录。 */
    public static final String PUBLIC_ROOT = "/storage/emulated/0/Download/VioletBox";

    private VioletPaths() {
    }

    /** Android 11+ 是否已获得「所有文件访问权限」。 */
    public static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳转到「所有文件访问权限」授权页（仅 Android 11+ 需要）。 */
    public static void requestAllFilesAccess(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (hasAllFilesAccess()) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(i);
        } catch (Exception ignored) {
            try {
                activity.startActivity(
                        new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored2) {
            }
        }
    }

    /**
     * 解析一个落盘子目录；优先公共目录，失败时用 root 兜底，最后退回私有目录。
     *
     * @param subDir 相对 {@link #PUBLIC_ROOT} 的子目录，可为 null 或 "" 表示根目录
     */
    public static File resolveDir(Context context, String subDir) {
        File dir = buildPublic(subDir);
        if (usable(dir)) return dir;

        // 有 root 就先建目录并放开权限，再试一次
        tryRootMkdir(dir);
        if (usable(dir)) return dir;

        File priv = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File base = priv != null ? priv : context.getFilesDir();
        File fallback = new File(base, subDir == null || subDir.isEmpty()
                ? "VioletBox" : "VioletBox/" + subDir);
        //noinspection ResultOfMethodIgnored
        fallback.mkdirs();
        return fallback;
    }

    /**
     * 把已经下载好的文件搬到公共目录（用于应用私有目录的兜底场景）。
     * 优先普通文件写入，失败则用 root 复制。返回最终可用的文件。
     */
    public static File publishToPublic(Context context, File src, String subDir) {
        if (src == null || !src.exists()) return src;
        File dir = buildPublic(subDir);
        if (!usable(dir)) tryRootMkdir(dir);
        File dst = new File(dir, src.getName());
        if (sameFile(src, dst)) return src;

        if (usable(dir)) {
            if (copyFile(src, dst) && dst.exists() && dst.length() == src.length()) {
                //noinspection ResultOfMethodIgnored
                dst.setReadable(true, false);
                return dst;
            }
        }
        // root 兜底：su 复制 + 放开权限
        String cmd = "mkdir -p '" + dir.getAbsolutePath() + "' && cp '" + src.getAbsolutePath()
                + "' '" + dst.getAbsolutePath() + "' && chmod 644 '" + dst.getAbsolutePath() + "'";
        SelinuxShellUtil.ShellResult r = SelinuxShellUtil.runSu(cmd, 20000);
        if (r.success && dst.exists()) return dst;
        new File(dir.getAbsolutePath()).mkdirs();
        return src;
    }

    /** 公共目录当前是否不可写（true = 需要引导授权）。 */
    public static boolean needPermissionGuide() {
        File dir = buildPublic(null);
        if (usable(dir)) return false;
        tryRootMkdir(dir);
        return !usable(dir);
    }

    // ------------------------------------------------------------------ 内部

    private static File buildPublic(String subDir) {
        File root = new File(PUBLIC_ROOT);
        if (subDir == null || subDir.trim().isEmpty()) return root;
        return new File(root, subDir.trim());
    }

    private static boolean usable(File dir) {
        try {
            if (dir == null) return false;
            if (!dir.exists() && !dir.mkdirs()) return false;
            return dir.isDirectory() && dir.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    private static void tryRootMkdir(File dir) {
        String cmd = "mkdir -p '" + dir.getAbsolutePath() + "' && chmod 777 '"
                + dir.getAbsolutePath() + "'";
        SelinuxShellUtil.runSu(cmd, 10000);
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static boolean copyFile(File src, File dst) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            if (dst.exists() && !dst.delete()) {
                // 覆盖失败就换个名字，避免半截文件
                dst = new File(dst.getParentFile(), System.currentTimeMillis() + "_" + dst.getName());
            }
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
