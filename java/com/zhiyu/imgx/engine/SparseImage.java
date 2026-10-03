package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Android sparse image 读写 —— 移植自 D.N.A3 的 pys/sparse_img.py / imgextractor.py。
 *
 * sparse 格式: 28 字节文件头 + 若干 chunk。chunk 类型:
 *   0xCAC1 RAW       —— chunk_sz * blk_sz 字节原始数据
 *   0xCAC2 FILL      —— 4 字节填充值, 重复展开
 *   0xCAC3 DONT_CARE —— 空洞, 输出全零
 */
public final class SparseImage {

    public static final int MAGIC = 0xED26FF3A;
    private static final long MAGIC_L = 0xED26FF3AL;
    public static final int CHUNK_RAW = 0xCAC1;
    public static final int CHUNK_FILL = 0xCAC2;
    public static final int CHUNK_DONT_CARE = 0xCAC3;

    private SparseImage() {}

    public static boolean isSparse(File f) {
        if (f == null || !f.isFile() || f.length() < 28) return false;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] m = new byte[4];
            raf.readFully(m);
            return (m[0] & 0xFF) == 0x3A && (m[1] & 0xFF) == 0xFF
                    && (m[2] & 0xFF) == 0x26 && (m[3] & 0xFF) == 0xED;
        } catch (IOException e) {
            return false;
        }
    }

    /** 将 sparse 镜像展开为 raw 镜像。 */
    public static File toRaw(File sparse, File rawOut, Progress p) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(sparse, "r");
             FileOutputStream out = new FileOutputStream(rawOut)) {
            byte[] hdr = new byte[28];
            Io.readFully(in, hdr, 0, 28);
            long magic = Io.u32(hdr, 0);
            if (magic != MAGIC_L) throw new IOException("不是 sparse 镜像 (magic=0x" + Long.toHexString(magic) + ")");
            // 布局 <I4H4I: magic(4) major(2) minor(2) file_hdr_sz(2) chunk_hdr_sz(2) blk_sz(4) total_blks(4) total_chunks(4) crc(4)
            int fileHdrSz = Io.u16(hdr, 8);
            int chunkHdrSz = Io.u16(hdr, 10);
            int blkSz = (int) Io.u32(hdr, 12);
            long totalBlks = Io.u32(hdr, 16);
            long totalChunks = Io.u32(hdr, 20);
            if (blkSz <= 0 || blkSz % 4 != 0) throw new IOException("非法块大小: " + blkSz);

            if (fileHdrSz > 28) in.seek(fileHdrSz);
            byte[] chunkHdr = new byte[12];
            byte[] fill = new byte[4];
            byte[] zeros = new byte[blkSz];
            long written = 0;

            for (long i = 0; i < totalChunks; i++) {
                Io.readFully(in, chunkHdr, 0, 12);
                int type = Io.u16(chunkHdr, 0);
                long chunkSz = Io.u32(chunkHdr, 4);
                long totalSz = Io.u32(chunkHdr, 8);
                long dataSz = totalSz - chunkHdrSz;
                if (chunkHdrSz > 12) in.seek(in.getFilePointer() + (chunkHdrSz - 12));

                long outBytes = chunkSz * blkSz;
                if (type == CHUNK_RAW) {
                    if (dataSz < outBytes) throw new IOException("RAW chunk 数据不足");
                    byte[] buf = new byte[(int) Math.min(outBytes, 1 << 20)];
                    long remain = outBytes;
                    while (remain > 0) {
                        int n = (int) Math.min(buf.length, remain);
                        Io.readFully(in, buf, 0, n);
                        out.write(buf, 0, n);
                        remain -= n;
                    }
                } else if (type == CHUNK_FILL) {
                    Io.readFully(in, fill, 0, 4);
                    long remain = outBytes;
                    while (remain > 0) {
                        int n = (int) Math.min(blkSz, remain);
                        int full = n / 4, rest = n % 4;
                        for (int k = 0; k < full; k++) out.write(fill);
                        if (rest > 0) out.write(fill, 0, rest);
                        remain -= n;
                    }
                } else if (type == CHUNK_DONT_CARE) {
                    long remain = outBytes;
                    while (remain > 0) {
                        int n = (int) Math.min(zeros.length, remain);
                        out.write(zeros, 0, n);
                        remain -= n;
                    }
                } else {
                    throw new IOException("不支持的 sparse chunk 类型 0x" + Integer.toHexString(type));
                }
                written += outBytes;
                if (p != null) p.progress((int) Math.min(100, written * 100 / Math.max(1, totalBlks * blkSz)));
            }
        }
        return rawOut;
    }

    /**
     * 将 raw 镜像打包为 sparse 镜像(用于"打包"页)。
     * 连续全零块 → FILL 块; 其余 → RAW 块(单块上限 maxChunkBlocks, 默认 512 块 = 2MB)。
     */
    public static void fromRaw(File raw, File sparseOut, Progress p) throws IOException {
        fromRaw(raw, sparseOut, 4096, 512, p);
    }

    public static void fromRaw(File raw, File sparseOut, int blkSz, int maxChunkBlocks, Progress p) throws IOException {
        long fileLen = raw.length();
        if (fileLen % blkSz != 0) throw new IOException("raw 大小 " + fileLen + " 不是块大小 " + blkSz + " 的整数倍");
        long totalBlks = fileLen / blkSz;
        if (totalBlks > 0xFFFFFFFFL) throw new IOException("镜像过大, 无法打包为 sparse v1");

        try (RandomAccessFile in = new RandomAccessFile(raw, "r");
             FileOutputStream fos = new FileOutputStream(sparseOut)) {

            byte[] hdr = new byte[28];          // 占位, 最后回填
            fos.write(hdr);

            byte[] zeroChunk = new byte[12];
            Io.putU16(zeroChunk, 0, CHUNK_FILL);
            Io.putU32(zeroChunk, 4, 0);
            Io.putU32(zeroChunk, 8, 16);

            byte[] rawChunk = new byte[12];
            Io.putU16(rawChunk, 0, CHUNK_RAW);
            Io.putU32(rawChunk, 4, 0);
            Io.putU32(rawChunk, 8, 0);

            byte[] buf = new byte[blkSz * maxChunkBlocks];
            long pos = 0;
            long chunks = 0;

            while (pos < fileLen) {
                int nBlks = (int) Math.min(maxChunkBlocks, totalBlks - pos / blkSz);
                Io.readFully(in, buf, 0, nBlks * blkSz);

                boolean allZero = true;
                outer:
                for (int i = 0; i < nBlks * blkSz; i += 16) {
                    for (int j = 0; j < 16 && i + j < nBlks * blkSz; j++) {
                        if (buf[i + j] != 0) { allZero = false; break outer; }
                    }
                }

                if (allZero) {
                    Io.putU32(zeroChunk, 4, nBlks);
                    fos.write(zeroChunk);
                    fos.write(new byte[]{0, 0, 0, 0});
                } else {
                    Io.putU32(rawChunk, 4, nBlks);
                    Io.putU32(rawChunk, 8, 12 + nBlks * blkSz);
                    fos.write(rawChunk);
                    fos.write(buf, 0, nBlks * blkSz);
                }
                chunks++;
                pos += nBlks * blkSz;
                if (p != null) p.progress((int) Math.min(100, pos * 100 / Math.max(1, fileLen)));
            }

            Io.putU32(hdr, 0, MAGIC);
            Io.putU16(hdr, 4, 1);   // major
            Io.putU16(hdr, 6, 0);   // minor
            Io.putU16(hdr, 8, 28);  // file_hdr_sz
            Io.putU16(hdr, 10, 12); // chunk_hdr_sz
            Io.putU32(hdr, 12, blkSz);
            Io.putU32(hdr, 16, totalBlks);
            Io.putU32(hdr, 20, chunks);
            Io.putU32(hdr, 24, 0);  // crc32 不校验
            try (RandomAccessFile raf = new RandomAccessFile(sparseOut, "rw")) {
                raf.seek(0);
                raf.write(hdr);
            }
        }
    }
}
