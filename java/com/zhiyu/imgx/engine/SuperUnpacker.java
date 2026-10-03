package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * super.img 动态分区解包 —— 移植自 D.N.A3 的 pys/lpunpack.py。
 *
 * 布局(AOSP liblp):
 *   [0, 4096)            保留
 *   [4096, 8192)         geometry(魔数 0x616c4467 "gDla")
 *   [8192, 12288)        geometry 备份
 *   [12288, ...)         metadata 槽位(primary/backup, 每槽 metadata_max_size)
 * metadata = header(80B) + 4 个表描述符(12B 各) + partitions/extents/groups/block_devices 表
 * 分区数据按 extent 的 target_data(扇区偏移) 从 super 物理偏移读取。
 */
public final class SuperUnpacker {

    private static final int LP_PARTITION_RESERVED_BYTES = 4096;
    private static final int LP_METADATA_GEOMETRY_MAGIC = 0x616C4467;
    private static final int LP_METADATA_GEOMETRY_SIZE = 4096;
    private static final int LP_METADATA_HEADER_MAGIC = 0x414C5030;
    private static final int LP_SECTOR_SIZE = 512;
    private static final int LP_TARGET_TYPE_LINEAR = 0;
    private static final int LP_TARGET_TYPE_ZERO = 1;

    public static class Extent {
        public long numSectors;
        public int targetType;
        public long targetData;    // 扇区
        public int targetSource;
    }

    public static class PartitionInfo {
        public String name;
        public int firstExtentIndex;
        public int numExtents;
        public List<Extent> extents = new ArrayList<>(); // 解析后填充
        public long size;
    }

    private static class Geometry {
        int magic;
        int metadataMaxSize, metadataSlotCount, logicalBlockSize;
    }

    private static class TableDesc {
        int offset, numEntries, entrySize;
    }

    private static class Metadata {
        long slotOffset;         // metadata 槽位起始偏移(表偏移的基准)
        int headerSize;
        TableDesc partitions = new TableDesc();
        TableDesc extents = new TableDesc();
        TableDesc groups = new TableDesc();
        TableDesc blockDevices = new TableDesc();
    }

    private SuperUnpacker() {}

    /** 读取 super.img 分区列表(名称+大小, 不落盘) */
    public static List<PartitionInfo> listPartitions(File superImg) throws IOException {
        File work = superImg;
        File tempRaw = null;
        try (RandomAccessFile raf = openMaybeSparse(superImg)) {
            Metadata md = readMetadata(raf);
            List<PartitionInfo> parts = readPartitions(raf, md);
            List<Extent> extents = readExtents(raf, md);
            resolve(parts, extents);
            return parts;
        } finally {
            if (tempRaw != null) Io.deleteRecursive(tempRaw);
        }
    }

    /** 解包 super.img 到 outDir, 输出 <分区名>.img; super 为 sparse 时自动展开。 */
    public static void unpack(File superImg, File outDir, Progress p) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建输出目录: " + outDir);
        File work = superImg;
        File tempRaw = null;
        try {
            if (SparseImage.isSparse(superImg)) {
                p.log("super.img 是 sparse 格式, 先展开为 raw...");
                tempRaw = File.createTempFile("super_unsparse", ".raw");
                tempRaw.deleteOnExit();
                SparseImage.toRaw(superImg, tempRaw, p);
                work = tempRaw;
            }
            try (RandomAccessFile raf = new RandomAccessFile(work, "r")) {
                Metadata md = readMetadata(raf);
                List<PartitionInfo> parts = readPartitions(raf, md);
                List<Extent> extents = readExtents(raf, md);
                resolve(parts, extents);

                int total = parts.size();
                int done = 0;
                for (PartitionInfo pi : parts) {
                    p.log("提取分区 [" + pi.name + "] ...");
                    File out = new File(outDir, pi.name + ".img");
                    long size = 0;
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        for (Extent ext : pi.extents) {
                            if (ext.targetType == LP_TARGET_TYPE_LINEAR) {
                                long len = ext.numSectors * LP_SECTOR_SIZE;
                                size += len;
                                copyRange(raf, fos, ext.targetData * LP_SECTOR_SIZE, len);
                            } else if (ext.targetType == LP_TARGET_TYPE_ZERO) {
                                long len = ext.numSectors * LP_SECTOR_SIZE;
                                size += len;
                                writeZeros(fos, len);
                            } else {
                                throw new IOException("分区 " + pi.name + " 含不支持的 extent 类型: " + ext.targetType);
                            }
                        }
                    }
                    pi.size = size;
                    p.log("  -> " + out.getName() + " (" + (size / 1048576) + " MB)");
                    done++;
                    p.progress(done * 100 / Math.max(1, total));
                }
            }
        } finally {
            if (tempRaw != null) Io.deleteRecursive(tempRaw);
        }
    }

    private static RandomAccessFile openMaybeSparse(File f) throws IOException {
        // 供 listPartitions 使用; sparse 时由调用方处理
        return new RandomAccessFile(f, "r");
    }

    private static void resolve(List<PartitionInfo> parts, List<Extent> extents) throws IOException {
        for (PartitionInfo pi : parts) {
            if (pi.firstExtentIndex + pi.numExtents > extents.size()) {
                throw new IOException("分区 " + pi.name + " extent 索引越界");
            }
            for (int i = 0; i < pi.numExtents; i++) {
                pi.extents.add(extents.get(pi.firstExtentIndex + i));
            }
        }
    }

    private static List<PartitionInfo> readPartitions(RandomAccessFile raf, Metadata md) throws IOException {
        List<PartitionInfo> parts = new ArrayList<>();
        for (int i = 0; i < md.partitions.numEntries; i++) {
            raf.seek(md.slotOffset + md.headerSize + md.partitions.offset + (long) i * md.partitions.entrySize);
            byte[] buf = new byte[md.partitions.entrySize];
            raf.readFully(buf);
            PartitionInfo pi = new PartitionInfo();
            int end = 0;
            while (end < 36 && buf[end] != 0) end++;
            pi.name = new String(buf, 0, end, "UTF-8");
            pi.firstExtentIndex = Io.i32(buf, 40);
            pi.numExtents = Io.i32(buf, 44);
            parts.add(pi);
        }
        return parts;
    }

    private static List<Extent> readExtents(RandomAccessFile raf, Metadata md) throws IOException {
        List<Extent> list = new ArrayList<>();
        for (int i = 0; i < md.extents.numEntries; i++) {
            raf.seek(md.slotOffset + md.headerSize + md.extents.offset + (long) i * md.extents.entrySize);
            byte[] buf = new byte[md.extents.entrySize];
            raf.readFully(buf);
            Extent e = new Extent();
            e.numSectors = Io.u64(buf, 0);
            e.targetType = Io.i32(buf, 8);
            e.targetData = Io.u64(buf, 12);
            e.targetSource = Io.i32(buf, 20);
            list.add(e);
        }
        return list;
    }

    private static void copyRange(RandomAccessFile raf, FileOutputStream fos, long offset, long len)
            throws IOException {
        raf.seek(offset);
        byte[] buf = new byte[1 << 20];
        long remain = len;
        while (remain > 0) {
            int n = (int) Math.min(buf.length, remain);
            raf.readFully(buf, 0, n);
            fos.write(buf, 0, n);
            remain -= n;
        }
    }

    private static void writeZeros(FileOutputStream fos, long len) throws IOException {
        byte[] zeros = new byte[1 << 20];
        long remain = len;
        while (remain > 0) {
            int n = (int) Math.min(zeros.length, remain);
            fos.write(zeros, 0, n);
            remain -= n;
        }
    }

    private static Metadata readMetadata(RandomAccessFile raf) throws IOException {
        Metadata md = new Metadata();
        raf.seek(LP_PARTITION_RESERVED_BYTES);
        byte[] g = new byte[LP_METADATA_GEOMETRY_SIZE];
        raf.readFully(g);
        Geometry geo = new Geometry();
        geo.magic = Io.i32(g, 0);
        if (geo.magic != LP_METADATA_GEOMETRY_MAGIC) {
            throw new IOException("super 镜像 geometry 魔数无效 (0x" + Integer.toHexString(geo.magic) + ")");
        }
        geo.metadataMaxSize = Io.i32(g, 40);
        geo.metadataSlotCount = Io.i32(g, 44);
        geo.logicalBlockSize = Io.i32(g, 48);
        if (geo.metadataSlotCount == 0) throw new IOException("metadata 槽位数为 0");

        long base = LP_PARTITION_RESERVED_BYTES + (LP_METADATA_GEOMETRY_SIZE * 2);
        boolean found = false;
        for (int slot = 0; slot < geo.metadataSlotCount && !found; slot++) {
            long off = base + (long) slot * geo.metadataMaxSize;
            raf.seek(off);
            byte[] hdr = new byte[80];
            raf.readFully(hdr);
            int magic = Io.i32(hdr, 0);
            if (magic != LP_METADATA_HEADER_MAGIC) continue;
            md.slotOffset = off;
            md.headerSize = Io.i32(hdr, 8);
            readDesc(raf, md.partitions);
            readDesc(raf, md.extents);
            readDesc(raf, md.groups);
            readDesc(raf, md.blockDevices);
            found = true;
        }
        if (!found) throw new IOException("super 镜像 metadata 魔数无效");
        return md;
    }

    private static void readDesc(RandomAccessFile raf, TableDesc td) throws IOException {
        byte[] b = new byte[12];
        raf.readFully(b);
        td.offset = Io.i32(b, 0);
        td.numEntries = Io.i32(b, 4);
        td.entrySize = Io.i32(b, 8);
    }
}
