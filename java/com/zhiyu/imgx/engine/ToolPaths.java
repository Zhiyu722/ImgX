package com.zhiyu.imgx.engine;

import java.io.File;

/**
 * 内置原生工具路径。宿主测试时指向 termux 的真实二进制, APP 内指向解压到私有目录的二进制。
 */
public class ToolPaths {
    public final File debugfs;
    public final File mke2fs;
    public final File brotli;
    public final File lz4;
    public final File magiskboot;
    public final File e2fsdroid;
    public final File lpmake;
    public final File extractErofs;
    public final File makeExt4fs;
    public final File payloadDumper;
    public final File libDir;

    public ToolPaths(File debugfs, File mke2fs, File brotli, File lz4, File magiskboot,
                     File e2fsdroid, File lpmake, File extractErofs, File makeExt4fs,
                     File payloadDumper, File libDir) {
        this.debugfs = debugfs;
        this.mke2fs = mke2fs;
        this.brotli = brotli;
        this.lz4 = lz4;
        this.magiskboot = magiskboot;
        this.e2fsdroid = e2fsdroid;
        this.lpmake = lpmake;
        this.extractErofs = extractErofs;
        this.makeExt4fs = makeExt4fs;
        this.payloadDumper = payloadDumper;
        this.libDir = libDir;
    }

    /** 宿主测试: 直接使用 termux 里的二进制。 */
    public static ToolPaths host() {
        String prefix = System.getenv("PREFIX");
        if (prefix == null) prefix = "/data/data/com.termux/files/usr";
        File lib = new File(prefix + "/lib");
        return new ToolPaths(
                new File(prefix + "/bin/debugfs"),
                new File(prefix + "/bin/mke2fs"),
                new File(prefix + "/bin/brotli"),
                new File(prefix + "/bin/lz4"),
                new File(prefix + "/bin/magiskboot"),
                new File(prefix + "/bin/e2fsdroid"),
                new File(prefix + "/bin/lpmake"),
                new File(prefix + "/bin/extract.erofs"),
                new File(prefix + "/bin/make_ext4fs"),
                new File(System.getenv("PDG") != null ? System.getenv("PDG") : "build/tools/payload-dumper-go"),
                lib);
    }

    public boolean complete() {
        return debugfs.exists() && mke2fs.exists() && brotli.exists();
    }
}
