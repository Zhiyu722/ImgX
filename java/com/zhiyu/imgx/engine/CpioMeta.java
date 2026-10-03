package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * cpio(newc "070701") 元数据读取器(不解析内容, 只读条目信息)。
 * 用于在解包时记录每个条目的 权限/属主/类型/软链目标, 打包时据此保证元数据不被破坏。
 */
public final class CpioMeta {

    public static final class Entry {
        public String name;
        public int mode;      // 权限位(不含类型)
        public int type;      // 0=文件 1=目录 2=软链 3=其它(设备节点等)
        public int uid, gid;
        public long size;
        public String linkTarget;
        /** 解包后磁盘上的实际权限(用于判断用户是否手动改过) */
        public int extractedMode = -1;

        public boolean isDir() { return type == 1; }
        public boolean isSymlink() { return type == 2; }
        public boolean isFile() { return type == 0; }
    }

    private CpioMeta() {}

    /** 读取 cpio 所有条目元数据(遇到异常条目即停止, 返回已读到的部分)。 */
    public static List<Entry> read(File cpio) throws IOException {
        List<Entry> list = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(cpio, "r")) {
            long len = raf.length();
            long pos = 0;
            byte[] hdr = new byte[110];
            while (pos + 110 <= len) {
                raf.seek(pos);
                raf.readFully(hdr);
                String magic = new String(hdr, 0, 6, StandardCharsets.US_ASCII);
                if (!magic.equals("070701") && !magic.equals("070702")) break;
                long mode = hex(hdr, 14);
                long uid = hex(hdr, 22);
                long gid = hex(hdr, 30);
                long size = hex(hdr, 54);
                long namesize = hex(hdr, 94);
                pos += 110;
                if (namesize <= 0 || namesize > 65536 || pos + namesize > len) break;
                byte[] nb = new byte[(int) namesize];
                raf.seek(pos);
                raf.readFully(nb);
                pos += namesize;
                pos = align4(pos);
                String name = new String(nb, 0, (int) namesize - 1, StandardCharsets.UTF_8);
                if (name.equals("TRAILER!!!")) break;

                Entry e = new Entry();
                e.name = name;
                e.mode = (int) (mode & 07777);
                long fmt = mode & 0170000;
                if (fmt == 0040000) e.type = 1;
                else if (fmt == 0120000) e.type = 2;
                else if (fmt == 0100000 || fmt == 0) e.type = 0;
                else e.type = 3;   // 设备节点/FIFO 等
                e.uid = (int) uid;
                e.gid = (int) gid;
                e.size = size;
                if (e.isSymlink() && size > 0 && size < 4096 && pos + size <= len) {
                    byte[] tb = new byte[(int) size];
                    raf.seek(pos);
                    raf.readFully(tb);
                    e.linkTarget = new String(tb, StandardCharsets.UTF_8);
                }
                list.add(e);
                pos += size;
                pos = align4(pos);
            }
        }
        return list;
    }

    /**
     * 写入清单文件。每行: type cpioMode uid gid size extractedMode [linkTarget] | path
     * extractedMode = 解包后磁盘上该条目的实际权限(magiskboot 提取后通常是默认值),
     * 用于在打包时判断"用户是否手动改过权限"。
     */
    public static void saveManifest(List<Entry> entries, File out, File extractedRoot)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            int extractedMode = -1;
            if (extractedRoot != null) {
                File f = new File(extractedRoot, e.name);
                if (f.exists()) extractedMode = modeOf(f);
            }
            sb.append(e.type).append(' ').append(e.mode).append(' ')
              .append(e.uid).append(' ').append(e.gid).append(' ').append(e.size).append(' ')
              .append(extractedMode).append(' ');
            if (e.isSymlink() && e.linkTarget != null) sb.append(e.linkTarget);
            sb.append(" |").append(e.name).append('\n');
        }
        Io.writeFile(out, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 读取文件的八进制权限(不含类型位)。 */
    public static int modeOf(File f) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.nio.file.Files.getPosixFilePermissions(f.toPath());
            int m = 0;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ)) m |= 0400;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) m |= 0200;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)) m |= 0100;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)) m |= 0040;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)) m |= 0020;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE)) m |= 0010;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) m |= 0004;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) m |= 0002;
            if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)) m |= 0001;
            return m;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 读取清单文件。 */
    public static List<Entry> loadManifest(File f) {
        List<Entry> list = new ArrayList<>();
        if (!f.exists()) return list;
        try {
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String line : all.split("\n")) {
                if (line.trim().isEmpty()) continue;
                int bar = line.indexOf(" |");
                if (bar < 0) continue;
                String[] p = line.substring(0, bar).split(" ");
                Entry e = new Entry();
                e.type = Integer.parseInt(p[0]);
                e.mode = Integer.parseInt(p[1]);
                e.uid = Integer.parseInt(p[2]);
                e.gid = Integer.parseInt(p[3]);
                e.size = Long.parseLong(p[4]);
                e.extractedMode = p.length > 5 ? Integer.parseInt(p[5]) : -1;
                if (e.type == 2 && p.length > 6) e.linkTarget = p[6];
                e.name = line.substring(bar + 2);
                list.add(e);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static long hex(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int c = b[off + i] & 0xFF;
            int d = (c >= '0' && c <= '9') ? c - '0'
                    : (c >= 'a' && c <= 'f') ? c - 'a' + 10
                    : (c >= 'A' && c <= 'F') ? c - 'A' + 10 : 0;
            v = (v << 4) | d;
        }
        return v;
    }

    private static long align4(long v) { return (v + 3) & ~3L; }
}
