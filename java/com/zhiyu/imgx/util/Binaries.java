package com.zhiyu.imgx.util;

import android.content.Context;
import android.os.Environment;

import com.zhiyu.imgx.engine.ToolPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 内置原生工具管理: 首次运行时把 assets/bin 下的二进制解压到应用私有目录并赋予执行权限。
 */
public final class Binaries {

    private static final String[] BINARIES = {
            "debugfs", "mke2fs", "brotli", "lz4", "magiskboot", "e2fsdroid",
            "lpmake", "extract.erofs", "make_ext4fs", "payload-dumper-go",
    };

    /** 随包分发的配置文件(也解压到 bin 目录) */
    private static final String[] CONFIGS = {
            "mke2fs.conf",
    };

    private static final String[] LIBS = {
            "libblkid.so", "libuuid.so", "libandroid-posix-semaphore.so",
            "libbrotlienc.so", "libbrotlidec.so", "libbrotlicommon.so",
            "liblz4.so", "libz.so.1", "libzstd.so.1", "libxxhash.so.0",
    };

    private Binaries() {}

    /** 解压二进制到 filesDir/bin, 返回 ToolPaths; 若解压过则直接复用。 */
    public static synchronized ToolPaths ensure(Context ctx, ProgressLog log) throws Exception {
        File dir = new File(ctx.getFilesDir(), "bin");
        File libDir = new File(dir, "lib");
        dir.mkdirs();
        libDir.mkdirs();

        // 版本变化时强制重新解压(重装后 SELinux 类别改变, 旧文件可能不可执行)
        int curVersion = -1;
        try {
            curVersion = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {}
        File verFile = new File(dir, ".version");
        int oldVersion = -1;
        if (verFile.exists()) {
            try { oldVersion = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(verFile.toPath())).trim()); }
            catch (Exception ignored) {}
        }
        boolean versionChanged = oldVersion != curVersion;
        ToolPaths paths = new ToolPaths(
                new File(dir, "debugfs"),
                new File(dir, "mke2fs"),
                new File(dir, "brotli"),
                new File(dir, "lz4"),
                new File(dir, "magiskboot"),
                new File(dir, "e2fsdroid"),
                new File(dir, "lpmake"),
                new File(dir, "extract.erofs"),
                new File(dir, "make_ext4fs"),
                new File(dir, "payload-dumper-go"),
                libDir);

        boolean needExtract = versionChanged;
        if (!needExtract) {
            for (String b : BINARIES) {
                File f = new File(dir, b);
                if (!f.exists() || f.length() == 0) { needExtract = true; break; }
            }
        }
        if (needExtract || !new File(dir, "mke2fs").exists()) {
            if (log != null) log.log("首次运行: 释放内置引擎工具 ...");
            for (String b : BINARIES) {
                extract(ctx, "bin/" + b, new File(dir, b), log);
            }
            for (String c : CONFIGS) {
                extract(ctx, "bin/" + c, new File(dir, c), log);
            }
            for (String l : LIBS) {
                extract(ctx, "bin/lib/" + l, new File(libDir, l), log);
            }
        }
        if (versionChanged) {
            try { java.nio.file.Files.write(verFile.toPath(), String.valueOf(curVersion).getBytes()); }
            catch (Exception ignored) {}
        }
        if (log != null) log.log("引擎工具就绪 ✓");
        return paths;
    }

    private static void extract(Context ctx, String asset, File out, ProgressLog log) throws Exception {
        if (out.exists() && out.length() > 0) return;
        try (InputStream in = ctx.getAssets().open(asset);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        out.setExecutable(true, false);
        out.setReadable(true, false);
        if (log != null) log.log("  释放 " + out.getName());
    }

    /** 默认输出目录 /sdcard/DNA/out */
    public static File defaultOutDir(Context ctx) {
        File sdcard = Environment.getExternalStorageDirectory();
        File rip = new File(sdcard, "ImgX");
        File out = new File(rip, "out");
        return out;
    }

    public interface ProgressLog {
        void log(String line);
    }
}
