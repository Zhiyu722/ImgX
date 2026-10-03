package com.zhiyu.imgx.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** 字节序 / IO 小工具。 */
public final class Io {

    private Io() {}

    // ---------- little-endian readers ----------
    public static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    public static int i16(byte[] b, int o) {
        return (short) u16(b, o);
    }

    public static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    public static int i32(byte[] b, int o) {
        return (int) u32(b, o);
    }

    public static long u64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFFL);
        return v;
    }

    // ---------- little-endian writers ----------
    public static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    public static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >>> 8) & 0xFF);
        b[o + 2] = (byte) ((v >>> 16) & 0xFF);
        b[o + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    public static void putU64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }

    // ---------- stream helpers ----------
    public static void readFully(RandomAccessFile raf, byte[] buf, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = raf.read(buf, off + n, len - n);
            if (r < 0) throw new IOException("文件提前结束(需要 " + len + " 字节, 已读 " + n + ")");
            n += r;
        }
    }

    public static void copy(InputStream in, OutputStream out, Progress p, long total) throws IOException {
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (p != null && total > 0) p.progress((int) Math.min(100, done * 100 / total));
        }
    }

    public static long filesSize(File dir) {
        long s = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) s += filesSize(f);
            else s += f.length();
        }
        return s;
    }

    public static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos, null, -1);
        return bos.toByteArray();
    }

    public static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }
}
