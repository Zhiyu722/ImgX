package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * new.dat → raw img —— 移植自 D.N.A3 的 pys/sdat2img.py (xpirt/luxi78/howllzhu)。
 *
 * 原理: transfer list(*.transfer.list) 第一行版本号, 第二行总块数, 之后每行一条命令;
 * 'new' 命令的块区间按顺序从 new.dat 顺序读取写入输出镜像的对应块位置。
 */
public final class Sdat2Img {

    public static final int BLOCK_SIZE = 4096;

    private Sdat2Img() {}

    /** @param transferList 如 system.transfer.list; @param newData 如 system.new.dat; @param out 输出 raw img */
    public static void convert(File transferList, File newData, File out, Progress p) throws IOException {
        List<String> lines = new ArrayList<>();
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(transferList), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        br.close();
        if (lines.size() < 2) throw new IOException("transfer list 内容不足");

        int version;
        long newBlocks;
        try {
            version = Integer.parseInt(lines.get(0));
            newBlocks = Long.parseLong(lines.get(1));
        } catch (NumberFormatException e) {
            throw new IOException("transfer list 格式错误: " + lines.get(0) + " / " + lines.get(1));
        }

        List<long[]> ranges = new ArrayList<>();   // [begin, end) 数据区间, 按 data 文件顺序
        boolean hasNew = false;
        for (int i = version >= 2 ? 4 : 2; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(" ");
            if (parts.length < 2) continue;
            String cmd = parts[0];
            if (!cmd.matches("[a-z]+")) continue;
            if (cmd.equals("new")) {
                parseRangeSet(parts[1], ranges);
                hasNew = true;
            }
            // erase / zero / free / stash 均只需跳过(新文件未写区域天然为零)
        }
        if (!hasNew) throw new IOException("transfer list 中没有 'new' 命令, 无法生成镜像");

        long maxBlock = 0;
        for (long[] r : ranges) maxBlock = Math.max(maxBlock, r[1]);
        long outSize = maxBlock * BLOCK_SIZE;
        if (outSize == 0) throw new IOException("镜像大小为 0");

        try (RandomAccessFile data = new RandomAccessFile(newData, "r");
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[BLOCK_SIZE * 1024]; // 4MB 缓冲
            long done = 0, total = newBlocks;
            for (long[] r : ranges) {
                long begin = r[0], end = r[1];
                long cursor = begin * BLOCK_SIZE;
                long remain = (end - begin) * BLOCK_SIZE;
                fos.getChannel().position(cursor);
                while (remain > 0) {
                    int n = (int) Math.min(buf.length, remain);
                    Io.readFully(data, buf, 0, n);
                    fos.write(buf, 0, n);
                    remain -= n;
                    done += n;
                    if (p != null && total > 0) p.progress((int) Math.min(100, done * 100 / (total * BLOCK_SIZE)));
                }
            }
            // 文件未写到的区域保持稀疏(零)
        }
    }

    /** "n,a,b,c,d" → [a,b),[c,d) ... 首元素是区间数量, 追加到 out */
    private static void parseRangeSet(String src, List<long[]> out) throws IOException {
        String[] items = src.split(",");
        List<Long> nums = new ArrayList<>();
        for (String s : items) {
            try { nums.add(Long.parseLong(s.trim())); }
            catch (NumberFormatException e) { throw new IOException("rangeset 解析失败: " + src); }
        }
        if (nums.isEmpty() || nums.get(0) != nums.size() - 1) {
            throw new IOException("rangeset 数量不符: " + src);
        }
        for (int i = 1; i + 1 < nums.size(); i += 2) {
            long b = nums.get(i), e = nums.get(i + 1);
            if (e > b) out.add(new long[]{b, e});
        }
    }

    /** 合并分段文件 xxx.dat.1 .. xxx.dat.N 到 xxx.dat */
    public static void mergeSegments(File firstSegment, File mergedOut, Progress p) throws IOException {
        String base = firstSegment.getAbsolutePath();
        int dot = base.lastIndexOf('.');
        String prefix = base.substring(0, dot); // 去掉 .N
        try (FileOutputStream fos = new FileOutputStream(mergedOut)) {
            int idx = 1;
            while (true) {
                File seg = new File(prefix + "." + idx);
                if (!seg.exists() || !seg.isFile()) break;
                long len = seg.length();
                try (RandomAccessFile raf = new RandomAccessFile(seg, "r")) {
                    byte[] buf = new byte[1 << 20];
                    long remain = len;
                    while (remain > 0) {
                        int n = (int) Math.min(buf.length, remain);
                        Io.readFully(raf, buf, 0, n);
                        fos.write(buf, 0, n);
                        remain -= n;
                    }
                }
                p.log("合并分段 " + seg.getName() + " (" + (len / 1048576) + " MB)");
                idx++;
            }
            if (idx == 1) throw new IOException("没有找到任何分段文件");
        }
    }
}
