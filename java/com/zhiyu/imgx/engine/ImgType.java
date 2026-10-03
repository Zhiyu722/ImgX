package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 镜像类型识别 —— 移植自 D.N.A3 的 pys/gettype.py。
 * 通过文件头魔数识别格式，不依赖扩展名。
 */
public final class ImgType {

    public enum Type {
        ZIP, OZIP, SEVEN_Z, EXT, SPARSE, EROFS, PAYLOAD, VBMETA, DTBO, ZST,
        DTB, EXE, ELF, BOOT, VENDOR_BOOT, AVB_FOOT, BZIP2, CHROMEOS, GZIP,
        LZ4_LEGACY, LZ4, ZOPFLI, XZ, LZMA, PNG, LOGO, SUPER, F2FS, ZSTD, UNKNOWN
    }

    // {magic, type, offset}
    private static final Object[][] FORMATS = {
            {new byte[]{0x50, 0x4B}, Type.ZIP, 0},
            {new byte[]{0x4F, 0x50, 0x50, 0x4F, 0x45, 0x4E, 0x43, 0x52, 0x59, 0x50, 0x54, 0x21}, Type.OZIP, 0},
            {new byte[]{0x37, 0x7A}, Type.SEVEN_Z, 0},
            {new byte[]{0x53, (byte)0xEF}, Type.EXT, 1080},          // ext2/3/4 superblock magic @0x438(1024+56)
            {new byte[]{0x3A, (byte)0xFF, 0x26, (byte)0xED}, Type.SPARSE, 0},  // 0xED26FF3A
            {new byte[]{(byte)0xE2, (byte)0xE1, (byte)0xF5, (byte)0xE0}, Type.EROFS, 1024},
            {new byte[]{0x43, 0x72, 0x41, 0x55}, Type.PAYLOAD, 0},    // CrAU
            {new byte[]{0x41, 0x56, 0x42, 0x30}, Type.VBMETA, 0},
            {new byte[]{(byte)0xD7, (byte)0xB7, (byte)0xAB, 0x1E}, Type.DTBO, 0},
            {new byte[]{0x28, (byte)0xB5, 0x2F, (byte)0xFD}, Type.ZSTD, 0},
            {(new byte[]{(byte)0xD0, 0x0D, (byte)0xFE, (byte)0xED}), Type.DTB, 0},
            {new byte[]{0x4D, 0x5A}, Type.EXE, 0},
            {new byte[]{0x2E, 0x45, 0x4C, 0x46}, Type.ELF, 0},
            {new byte[]{0x41, 0x4E, 0x44, 0x52, 0x4F, 0x49, 0x44, 0x21}, Type.BOOT, 0},
            {new byte[]{0x56, 0x4E, 0x44, 0x52, 0x42, 0x4F, 0x4F, 0x54}, Type.VENDOR_BOOT, 0},
            {new byte[]{0x41, 0x56, 0x42, 0x66}, Type.AVB_FOOT, 0},
            {new byte[]{0x42, 0x5A, 0x68}, Type.BZIP2, 0},
            {new byte[]{0x43, 0x48, 0x52, 0x4F, 0x4D, 0x45, 0x4F, 0x53}, Type.CHROMEOS, 0},
            {new byte[]{0x1F, (byte)0x8B}, Type.GZIP, 0},
            {new byte[]{0x02, 0x21, 0x4C, 0x18}, Type.LZ4_LEGACY, 0},
            {new byte[]{0x03, 0x21, 0x4C, 0x18}, Type.LZ4, 0},
            {new byte[]{0x04, 0x22, 0x4D, 0x18}, Type.LZ4, 0},
            {new byte[]{(byte)0xFD, 0x37, 0x7A, 0x58, 0x5A}, Type.XZ, 0},
            {new byte[]{0x5D, 0x00, 0x00, 0x00, 0x04, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}, Type.LZMA, 0},
            {new byte[]{0x67, 0x44, 0x6C, 0x61}, Type.SUPER, 4096},   // "gDla" @4096 (LP_METADATA_GEOMETRY_MAGIC)
            {new byte[]{0x10, 0x20, (byte)0xF5, (byte)0xF2}, Type.F2FS, 1024},
    };

    public static Type detect(File file) {
        if (file == null || !file.exists() || !file.isFile()) return Type.UNKNOWN;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            for (Object[] f : FORMATS) {
                byte[] magic = (byte[]) f[0];
                int offset = (Integer) f[2];
                raf.seek(offset);
                byte[] buf = new byte[magic.length];
                int n = raf.read(buf);
                if (n != magic.length) continue;
                boolean ok = true;
                for (int i = 0; i < magic.length; i++) {
                    if (buf[i] != magic[i]) { ok = false; break; }
                }
                if (ok) return (Type) f[1];
            }
        } catch (IOException ignored) {
        }
        return Type.UNKNOWN;
    }

    public static String label(Type t) {
        switch (t) {
            case ZIP: return "Zip 卡刷包";
            case OZIP: return "Oppo 加密包";
            case SEVEN_Z: return "7z 压缩包";
            case EXT: return "ext2/3/4 镜像";
            case SPARSE: return "Android sparse 镜像";
            case EROFS: return "erofs 镜像";
            case PAYLOAD: return "payload.bin OTA 包";
            case VBMETA: return "vbmeta 镜像";
            case DTBO: return "dtbo 镜像";
            case ZSTD: return "zstd 压缩流";
            case DTB: return "dtb 设备树";
            case BOOT: return "boot 镜像";
            case VENDOR_BOOT: return "vendor_boot 镜像";
            case GZIP: return "gzip 压缩流";
            case LZ4: case LZ4_LEGACY: return "lz4 压缩流";
            case XZ: return "xz 压缩流";
            case BZIP2: return "bzip2 压缩流";
            case SUPER: return "super 动态分区镜像";
            case F2FS: return "f2fs 镜像";
            default: return "未知格式";
        }
    }
}
