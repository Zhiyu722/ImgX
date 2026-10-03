package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 解包 / 打包总调度 —— 相当于 D.N.A3 的 cyrus.py 核心流程。
 * 所有逻辑均为纯 Java + 内置小工具, 无需 root。
 */
public final class ImgxEngine {

    private ImgxEngine() {}

    // ======================= 解包 =======================

    public static void unpack(File input, File outRoot, boolean unpackPartitions, ToolPaths tools, Progress p)
            throws IOException {
        if (!input.exists() || !input.isFile()) throw new IOException("文件不存在: " + input);
        if (!outRoot.exists() && !outRoot.mkdirs()) {
            // 逐级尝试创建, 并给出明确原因
            File parent = outRoot.getParentFile();
            while (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) break;
                parent = parent.getParentFile();
            }
            if (!outRoot.mkdirs()) {
                throw new IOException("无法创建输出目录: " + outRoot
                        + "\n可能原因: 存储权限不足(请授予「所有文件访问」) 或 该目录不可写(换个目录, 如 /sdcard/ImgX/out)");
            }
        }

        p.log("══ 解包: " + input.getName() + " ══");
        ImgType.Type t = ImgType.detect(input);
        p.log("类型: " + ImgType.label(t));

        switch (t) {
            case EXT: {
                File dir = new File(outRoot, baseName(input));
                Ext4Tool.extract(input, dir, tools, p);
                break;
            }
            case SPARSE: {
                File raw = new File(outRoot, baseName(input) + ".raw");
                p.log("sparse → raw 展开中 ...");
                SparseImage.toRaw(input, raw, p);
                p.log("已生成 raw: " + raw.getName() + " (" + (raw.length() / 1048576) + " MB)");
                if (isExt4(raw)) {
                    File dir = new File(outRoot, baseName(input));
                    Ext4Tool.extract(raw, dir, tools, p);
                } else {
                    p.log("该 raw 不是 ext4, 仅输出 raw 镜像");
                }
                break;
            }
            case SUPER: {
                File dir = new File(outRoot, baseName(input) + "_super");
                SuperUnpacker.unpack(input, dir, p);
                if (unpackPartitions) {
                    unpackExt4Partitions(dir, dir, tools, p);
                } else {
                    p.log("提示: 开启「自动解包分区」可继续解出分区内容");
                }
                break;
            }
            case PAYLOAD: {
                File dir = new File(outRoot, baseName(input) + "_payload");
                PayloadTool.extract(input, dir, tools, p);
                if (unpackPartitions) {
                    unpackExt4Partitions(dir, dir, tools, p);
                } else {
                    p.log("提示: 开启「自动解包分区」可继续解出分区内容");
                }
                break;
            }
            case BOOT:
            case VENDOR_BOOT: {
                File dir = new File(outRoot, baseName(input));
                BootImage.unpack(input, dir, tools, p);
                break;
            }
            case ZIP: {
                unpackZip(input, outRoot, unpackPartitions, tools, p);
                break;
            }
            case GZIP: {
                File out = new File(outRoot, stripExt(input.getName()));
                p.log("gzip 解压 → " + out.getName());
                try (InputStream in = new GZIPInputStream(new FileInputStream(input));
                     OutputStream os = new FileOutputStream(out)) {
                    Io.copy(in, os, null, -1);
                }
                unpack(out, outRoot, unpackPartitions, tools, p);
                break;
            }
            case VBMETA: case DTBO: case DTB: case LOGO: {
                p.log("该格式无需解包, 复制保留原文件");
                File out = new File(outRoot, input.getName());
                copyFile(input, out);
                break;
            }
            case EROFS: {
                File dir = new File(outRoot, baseName(input));
                ErofsTool.extract(input, dir, tools, p);
                break;
            }
            case OZIP:
                throw new IOException("Oppo 加密包(ozip)需要解密密钥, 暂不支持");
            case SEVEN_Z:
                throw new IOException("7z 压缩包暂不支持, 请先解压");
            case ZSTD: case XZ: case LZ4: case LZ4_LEGACY: case BZIP2:
                throw new IOException("该压缩格式暂不支持, 请先解压后再解包");
            default:
                throw new IOException("无法识别的文件格式");
        }
        p.log("══ 完成 ══");
        p.done(true, "解包完成 → " + outRoot.getAbsolutePath());
    }

    private static void unpackZip(File zip, File outRoot, boolean unpackPartitions, ToolPaths tools, Progress p)
            throws IOException {
        File workDir = new File(outRoot, "zip_work_" + baseName(zip));
        if (!workDir.exists() && !workDir.mkdirs()) throw new IOException("无法创建临时目录");

        List<File> tasks = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String lower = name.toLowerCase();
                boolean interesting = lower.endsWith("payload.bin")
                        || lower.endsWith(".img")
                        || lower.endsWith(".dat")
                        || lower.endsWith(".dat.br")
                        || lower.matches(".*\\.dat\\.\\d+$")
                        || lower.endsWith(".br")
                        || lower.endsWith("transfer.list")
                        || lower.endsWith(".dat.bin");
                if (!interesting) continue;
                // 跳过增量包 .patch.dat
                if (lower.endsWith("patch.dat") || lower.endsWith("patch.dat.br")) {
                    p.log("检测到增量包 patch.dat —— 增量包需要旧镜像才能合并, 跳过");
                    continue;
                }
                File target = new File(workDir, name.replace('/', '_'));
                p.log("从 zip 提取: " + name);
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(target)) {
                    Io.copy(in, fos, null, -1);
                }
                tasks.add(target);
            }
        }

        if (tasks.isEmpty()) {
            p.log("zip 内未发现可解包的镜像文件");
            throw new IOException("zip 中没有找到 payload.bin / *.img / *.dat 等文件");
        }

        for (File f : tasks) {
            p.log("── 处理: " + f.getName() + " ──");
            try {
                handleDatChain(f, outRoot, unpackPartitions, tools, p);
            } catch (Exception ex) {
                p.log("[警告] " + f.getName() + " 处理失败: " + ex.getMessage());
            }
        }
        Io.deleteRecursive(workDir);
    }

    /** 处理 .dat / .dat.br / .dat.N / .br / .img 链 */
    private static void handleDatChain(File f, File outRoot, boolean unpackPartitions, ToolPaths tools, Progress p)
            throws IOException {
        String lower = f.getName().toLowerCase();
        if (lower.endsWith(".dat.br")) {
            File dat = new File(f.getParentFile(), stripExt(f.getName()));
            BrotliTool.decompress(f, dat, tools, p);
            handleDatChain(dat, outRoot, unpackPartitions, tools, p);
            return;
        }
        if (lower.endsWith(".br")) {
            File out = new File(f.getParentFile(), stripExt(f.getName()));
            BrotliTool.decompress(f, out, tools, p);
            unpack(out, outRoot, unpackPartitions, tools, p);
            return;
        }
        if (lower.matches(".*\\.dat\\.\\d+$")) {
            File merged = new File(f.getParentFile(), stripExt(f.getName()));
            Sdat2Img.mergeSegments(f, merged, p);
            handleDatChain(merged, outRoot, unpackPartitions, tools, p);
            return;
        }
        if (lower.endsWith(".dat") || lower.endsWith(".dat.bin")) {
            // 寻找配套 transfer list
            String stem = stemOf(f.getName());   // system.new.dat → system
            File tl = new File(f.getParentFile(), stem + ".transfer.list");
            if (!tl.exists()) {
                // 尝试去掉 .new 再找
                String alt = f.getName().replace(".new.dat", ".transfer.list")
                        .replace(".dat", ".transfer.list");
                tl = new File(f.getParentFile(), alt);
            }
            if (tl.exists() && tl.isFile()) {
                p.log("找到 transfer list: " + tl.getName());
                File img = new File(f.getParentFile(), stem + ".img");
                Sdat2Img.convert(tl, f, img, p);
                p.log("new.dat → " + img.getName() + " (" + (img.length() / 1048576) + " MB)");
                unpack(img, outRoot, unpackPartitions, tools, p);
            } else {
                p.log("未找到 transfer list, 尝试按 raw 镜像解包 ...");
                unpack(f, outRoot, unpackPartitions, tools, p);
            }
            return;
        }
        if (lower.endsWith(".img")) {
            unpack(f, outRoot, unpackPartitions, tools, p);
            return;
        }
        if (lower.endsWith("payload.bin")) {
            unpack(f, outRoot, unpackPartitions, tools, p);
            return;
        }
        unpack(f, outRoot, unpackPartitions, tools, p);
    }

    /** payload / super 解出分区后, 自动把 ext4 分区解包为目录 */
    private static void unpackExt4Partitions(File partsDir, File outRoot, ToolPaths tools, Progress p)
            throws IOException {
        File[] imgs = partsDir.listFiles((d, n) -> n.endsWith(".img"));
        if (imgs == null) return;
        for (File img : imgs) {
            File raw = img;
            File tempRaw = null;
            try {
                if (SparseImage.isSparse(img)) {
                    p.log("展开 sparse 分区 " + img.getName() + " ...");
                    tempRaw = File.createTempFile("part", ".raw");
                    tempRaw.deleteOnExit();
                    SparseImage.toRaw(img, tempRaw, p);
                    raw = tempRaw;
                }
                if (isExt4(raw)) {
                    File dir = new File(outRoot, baseName(img));
                    p.log("── 解包分区 " + img.getName() + " → " + dir.getName() + "/ ──");
                    Ext4Tool.extract(raw, dir, tools, p);
                } else {
                    p.log("分区 " + img.getName() + " 不是 ext4, 保留 img 文件");
                }
            } catch (Exception e) {
                p.log("[警告] 分区 " + img.getName() + " 解包失败: " + e.getMessage());
            } finally {
                if (tempRaw != null) Io.deleteRecursive(tempRaw);
            }
        }
    }

    // ======================= 打包 =======================

    /** 目录 → ext4 镜像 */
    public static void packExt4(File srcDir, File outImg, String label, ToolPaths tools, Progress p)
            throws IOException {
        p.log("══ 打包: " + srcDir.getName() + " → ext4 ══");
        Ext4Tool.pack(srcDir, outImg, label, tools, p);
        p.done(true, "打包完成 → " + outImg.getAbsolutePath());
    }

    /** raw 镜像 → sparse 镜像 */
    public static void packSparse(File raw, File outSparse, Progress p) throws IOException {
        p.log("══ 打包: " + raw.getName() + " → sparse ══");
        SparseImage.fromRaw(raw, outSparse, p);
        p.log("已生成 sparse: " + outSparse.getName() + " (" + (outSparse.length() / 1048576) + " MB)");
        p.done(true, "打包完成 → " + outSparse.getAbsolutePath());
    }

    /** 解包目录 → boot 镜像 */
    public static void packBoot(File bootDir, File outImg, ToolPaths tools, Progress p) throws IOException {
        p.log("══ 打包: " + bootDir.getName() + " → boot.img ══");
        BootImage.pack(bootDir, outImg, tools, p);
        p.done(true, "打包完成 → " + outImg.getAbsolutePath());
    }

    // ======================= 工具 =======================

    public static boolean isExt4(File f) {
        if (f == null || !f.isFile() || f.length() < 1082) return false;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            raf.seek(1080);
            int b0 = raf.readUnsignedByte();
            int b1 = raf.readUnsignedByte();
            return ((b0 & 0xFF) | ((b1 & 0xFF) << 8)) == 0xEF53; // 小端
        } catch (IOException e) {
            return false;
        }
    }

    /** 去掉扩展名(最后一个点之后全部去掉) */
    public static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** system.new.dat → system (去掉 .new 再 strip) */
    private static String stemOf(String name) {
        String s = name;
        if (s.endsWith(".dat.bin")) s = s.substring(0, s.length() - 8);
        else if (s.endsWith(".dat")) s = s.substring(0, s.length() - 4);
        if (s.endsWith(".new")) s = s.substring(0, s.length() - 4);
        return s;
    }

    public static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n;
    }

    private static void copyFile(File in, File out) throws IOException {
        try (InputStream is = new FileInputStream(in); FileOutputStream os = new FileOutputStream(out)) {
            Io.copy(is, os, null, -1);
        }
    }
}
