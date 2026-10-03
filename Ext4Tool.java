package com.zhiyu.dna.engine;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * ext4 镜像处理 —— 基于内置 e2fsprogs:
 *   解包: debugfs -R "rdump / <outdir>" <img>
 *   打包: mke2fs -t ext4 -b 4096 -d <srcdir> <img> <size>
 */
public final class Ext4Tool {

    private Ext4Tool() {}

    /** 解包 ext4 raw 镜像到 outDir。 */
    public static void extract(File img, File outDir, ToolPaths tools, Progress p) throws IOException {
        File raw = img;
        File tempRaw = null;
        try {
            if (SparseImage.isSparse(img)) {
                p.log("sparse 镜像 → 先展开为 raw ...");
                tempRaw = File.createTempFile("unsparse", ".raw");
                tempRaw.deleteOnExit();
                SparseImage.toRaw(img, tempRaw, p);
                raw = tempRaw;
            }
            if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建目录: " + outDir);

            // 校验 ext4 魔数(小端 0xEF53)
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(raw, "r")) {
                raf.seek(1080);
                int b0 = raf.readUnsignedByte();
                int b1 = raf.readUnsignedByte();
                int magic = (b0 & 0xFF) | ((b1 & 0xFF) << 8);
                if (magic != 0xEF53) {
                    throw new IOException("不是 ext2/3/4 镜像 (魔数 0x" + Integer.toHexString(magic) + ")");
                }
            }

            p.log("调用 debugfs 提取 ext4 文件系统 ...");
            File rdumpDir = new File(outDir, "rdump");
            rdumpDir.mkdirs();
            List<String> cmd = Exec.cmd(tools.debugfs.getAbsolutePath(),
                    "-R", "rdump / " + rdumpDir.getAbsolutePath(),
                    raw.getAbsolutePath());
            // 过滤无 root 时的 chown 警告(不影响提取结果, 避免日志刷屏)
            int code = Exec.run(tools.libDir, p, cmd, line ->
                    !line.contains("Operation not permitted")
                            && !line.contains("changing ownership")
                            && !line.contains("rdump:")
                            && !line.contains("dump_file:")
                            && !line.contains("Error while writing"));
            if (code != 0) throw new IOException("debugfs 提取失败 (exit " + code + ")");
            // 记录原镜像大小, 供重新打包时保持原大小
            try {
                Io.writeFile(new File(outDir, "__image_size.txt"),
                        String.valueOf(raw.length()).getBytes());
            } catch (Exception ignored) {}
            p.log("ext4 提取完成 → " + rdumpDir.getAbsolutePath());
        } finally {
            if (tempRaw != null) Io.deleteRecursive(tempRaw);
        }
    }

    /** 从源目录向上查找解包时记录的原镜像大小; 找不到则返回 calcSize。 */
    private static long findOrigSize(File srcDir, long calcSize) {
        File d = srcDir;
        for (int i = 0; i < 6 && d != null; i++) { // 最多向上找 6 层
            File f = new File(d, "__image_size.txt");
            if (f.exists()) {
                try {
                    String t = new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
                    long v = Long.parseLong(t);
                    if (v > 0) return (v + 4095) / 4096 * 4096;
                } catch (Exception ignored) {}
            }
            d = d.getParentFile();
        }
        return calcSize;
    }

    /** 打包目录为 ext4 镜像(含 fs_config 与 selinux 上下文, 解包→打包可引导)。 */
    public static void pack(File srcDir, File outImg, String label, ToolPaths tools, Progress p)
            throws IOException {
        if (!srcDir.isDirectory()) throw new IOException("源目录无效: " + srcDir);
        long fileSize = Io.filesSize(srcDir);
        if (fileSize == 0) throw new IOException("源目录为空");
        long calcSize = fileSize * 12 / 10 + (20L << 20); // 1.2x + 20MB 余量
        calcSize = (calcSize + 4095) / 4096 * 4096;
        if (calcSize < (64L << 20)) calcSize = 64L << 20;
        // 优先使用原镜像大小(解包时记录), 保证打包后大小与原版一致
        long size = findOrigSize(srcDir, calcSize);
        size = Math.max(size, calcSize);
        if (size != calcSize) {
            p.log("使用原镜像大小: " + (size / 1048576) + " MB");
        }

        p.log("计算大小: 文件 " + (fileSize / 1048576) + " MB → 镜像 " + (size / 1048576) + " MB");
        p.log("调用 mke2fs 生成 ext4 ...");
        List<String> cmd = Exec.cmd(tools.mke2fs.getAbsolutePath(),
                "-q", "-t", "ext4", "-b", "4096", "-d", srcDir.getAbsolutePath(),
                "-L", label == null || label.isEmpty() ? "DNA" : label,
                outImg.getAbsolutePath(), String.valueOf(size / 4096)); // 块数(4K/块), 传字节会被当成块数导致镜像虚大数 TB
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("mke2fs 打包失败 (exit " + code + ")");

        // 用 e2fsdroid 应用 fs_config 和 selinux 上下文(关键: 保证打包后可引导)
        File fsConfig = new File(srcDir.getParentFile(), "config/" + label + "_fs_config");
        File contexts = new File(srcDir.getParentFile(), "config/" + label + "_contexts");
        if (fsConfig.exists() || contexts.exists()) {
            p.log("应用 fs_config 与 selinux 上下文 ...");
            StringBuilder e2 = new StringBuilder(tools.e2fsdroid.getAbsolutePath());
            e2.append(" -f ").append(srcDir.getAbsolutePath());
            e2.append(" -a /").append(label);
            if (contexts.exists()) e2.append(" -S ").append(contexts.getAbsolutePath());
            if (fsConfig.exists()) e2.append(" -C ").append(fsConfig.getAbsolutePath());
            e2.append(" ").append(outImg.getAbsolutePath());
            List<String> e2cmd = Exec.cmd("sh", "-c", e2.toString());
            int e2code = Exec.run(tools.libDir, p, e2cmd);
            if (e2code != 0) p.log("警告: e2fsdroid 应用配置失败 (exit " + e2code + "), 镜像可能无法引导");
        } else {
            p.log("提示: 未找到 fs_config/contexts, 打包的镜像可能无法引导(请先解包再用打包功能)");
        }
        p.log("ext4 镜像已生成 → " + outImg.getAbsolutePath());
    }
}
