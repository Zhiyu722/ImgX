package com.zhiyu.imgx.engine;

import java.io.File;

/**
 * 内置原生工具路径。宿主测试时指向 termux 的真实二进制, APP 内指向解压到私有目录的二进制。
 */
public class ToolPaths {
    public final File debugfs;
    public final File mke2fs;
    public final File brotli;
    public final File magiskboot;
    public final File extractErofs;
    public final File makeExt4fs;
    public final File payloadDumper;
    public final File img2simg;     // TIK: raw → sparse
    public final File mkfsErofs;    // TIK: 目录 → erofs
    public final File libDir;

    public ToolPaths(File debugfs, File mke2fs, File brotli, File magiskboot,
                     File extractErofs, File makeExt4fs,
                     File payloadDumper, File img2simg, File mkfsErofs, File libDir) {
        this.debugfs = debugfs;
        this.mke2fs = mke2fs;
        this.brotli = brotli;
        this.magiskboot = magiskboot;
        this.extractErofs = extractErofs;
        this.makeExt4fs = makeExt4fs;
        this.payloadDumper = payloadDumper;
        this.img2simg = img2simg;
        this.mkfsErofs = mkfsErofs;
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
                new File(prefix + "/bin/magiskboot"),
                new File(prefix + "/bin/extract.erofs"),
                new File(prefix + "/bin/make_ext4fs"),
                new File(System.getenv("PDG") != null ? System.getenv("PDG") : "build/tools/payload-dumper-go"),
                new File(prefix + "/bin/img2simg"),
                new File(prefix + "/bin/mkfs.erofs"),
                lib);
    }

    public boolean complete() {
        return debugfs.exists() && mke2fs.exists() && brotli.exists();
    }
}
