package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ext4 镜像处理 —— 基于内置 e2fsprogs:
 *   解包: debugfs -R "rdump / <outdir>" <img>
 *   打包: mke2fs -t ext4 -b 4096 -d <srcdir> <img> <size>
 *
 * 权限保留(无 root, 不用 e2fsdroid):
 *   解包时从原镜像导出 每文件 uid/gid/权限 + security.selinux 上下文 → config/img_*
 *   打包时用 debugfs 批量 set_inode_field / ea_set 写回, 保证重打包镜像属主/上下文与原版一致
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
            // 导出 uid/gid/权限 + SELinux 上下文(重打包时恢复权限的关键, 失败不阻塞解包)
            try {
                generateConfig(raw, outDir, tools, p);
            } catch (Exception e) {
                p.log("警告: 导出属主/SELinux 配置失败: " + e.getMessage()
                        + " (重打包可能丢失原权限)");
            }
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
                "-L", label == null || label.isEmpty() ? "ImgX" : label,
                outImg.getAbsolutePath(), String.valueOf(size / 4096)); // 块数(4K/块), 传字节会被当成块数导致镜像虚大数 TB
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("mke2fs 打包失败 (exit " + code + ")");

        // 用解包时导出的 fs_config + SELinux 上下文恢复文件属主与安全上下文
        applyConfig(outImg, srcDir, label, tools, p);
        p.log("ext4 镜像已生成 → " + outImg.getAbsolutePath());
    }

    // ==================== 权限/上下文: 导出与应用(替代 e2fsdroid) ====================

    private static final Pattern LS_LINE = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\S+)\\s+\\(\\d+\\)\\s+(\\d+)\\s+(\\d+)\\s+\\d+\\s+\\S+\\s+\\S+\\s+(.+?)\\s*$");

    /** 解包后: 从原镜像递归读取每文件 inode/mode/uid/gid, 并用批处理 stat 抓 security.selinux, 写入 config/img_*。 */
    static void generateConfig(File img, File outDir, ToolPaths tools, Progress p) throws IOException {
        File cfgDir = new File(outDir, "config");
        if (!cfgDir.exists() && !cfgDir.mkdirs()) throw new IOException("无法创建 config 目录");
        p.log("正在读取文件属主与 SELinux 上下文 ...");

        File script = File.createTempFile("dbgcfg", ".cmds");
        script.deleteOnExit();
        Map<String, long[]> tree = new LinkedHashMap<>();   // path -> [inode, mode, uid, gid]
        List<String> dirs = new ArrayList<>();
        dirs.add("/");

        for (int guard = 0; guard < 500 && !dirs.isEmpty(); guard++) {
            List<String> batch = new ArrayList<>(dirs);
            dirs.clear();
            for (int i = 0; i < batch.size(); i += 40) {   // 每会话最多 40 个 ls, 减少进程开销
                StringBuilder sc = new StringBuilder();
                List<String> sub = batch.subList(i, Math.min(i + 40, batch.size()));
                for (String d : sub) sc.append("ls -l ").append(d).append('\n');
                Io.writeFile(script, sc.toString().getBytes());
                String out = Exec.capture(tools.libDir, Exec.cmd(
                        tools.debugfs.getAbsolutePath(), "-f", script.getAbsolutePath(),
                        img.getAbsolutePath()));
                String[] lines = out.split("\n");
                List<Integer> dot = new ArrayList<>();
                for (int k = 0; k < lines.length; k++) {
                    Matcher m = LS_LINE.matcher(lines[k]);
                    if (m.matches() && ".".equals(m.group(5))) dot.add(k);
                }
                for (int b = 0; b < sub.size() && b < dot.size(); b++) {
                    int s = dot.get(b);
                    int e = (b + 1 < dot.size()) ? dot.get(b + 1) : lines.length;
                    String dir = sub.get(b);
                    for (int k = s; k < e; k++) {
                        Matcher m = LS_LINE.matcher(lines[k]);
                        if (!m.matches()) continue;
                        String name = m.group(5);
                        long ino = Long.parseLong(m.group(1));
                        long mode = Long.parseLong(m.group(2), 8);
                        long uid = Long.parseLong(m.group(3));
                        long gid = Long.parseLong(m.group(4));
                        String path;
                        if (".".equals(name)) {
                            if (!"/".equals(dir)) continue;   // 非根的 "." 跳过
                            path = "/";                        // 根目录自身也要记录
                        } else if ("..".equals(name)) {
                            continue;
                        } else {
                            path = "/".equals(dir) ? "/" + name : dir + "/" + name;
                        }
                        tree.put(path, new long[]{ino, mode, uid, gid});
                        if (((mode >> 12) & 0xF) == 4 && !"/".equals(path)) dirs.add(path);
                    }
                }
            }
        }

        // fs_config: <path> <uid> <gid> <mode八进制> 0
        StringBuilder fs = new StringBuilder();
        for (Map.Entry<String, long[]> e : tree.entrySet()) {
            long[] v = e.getValue();
            fs.append(e.getKey()).append(' ').append(v[2]).append(' ').append(v[3])
                    .append(" 0").append(Long.toOctalString(v[1] & 07777L)).append(" 0\n");
        }
        Io.writeFile(new File(cfgDir, "img_fs_config"), fs.toString().getBytes());

        // SELinux 上下文: 一次会话批量 stat
        StringBuilder sc = new StringBuilder();
        for (String path : tree.keySet()) sc.append("stat ").append(path).append('\n');
        Io.writeFile(script, sc.toString().getBytes());
        String out = Exec.capture(tools.libDir, Exec.cmd(
                tools.debugfs.getAbsolutePath(), "-f", script.getAbsolutePath(),
                img.getAbsolutePath()));
        Map<Long, String> ctxByIno = new HashMap<>();
        long curIno = -1;
        for (String ln : out.split("\n")) {
            Matcher mi = Pattern.compile("^Inode:\\s*(\\d+)").matcher(ln);
            if (mi.find()) curIno = Long.parseLong(mi.group(1));
            Matcher mc = Pattern.compile("security\\.selinux \\(\\d+\\) = \"([^\"]+)\"").matcher(ln);
            if (mc.find() && curIno >= 0) ctxByIno.put(curIno, mc.group(1));
        }
        // 上下文按路径长度降序(长路径优先, 与 AOSP file_contexts 语义一致)
        java.util.TreeMap<Integer, List<String>> byLen =
                new java.util.TreeMap<>(java.util.Collections.reverseOrder());
        for (Map.Entry<String, long[]> e : tree.entrySet()) {
            String ctx = ctxByIno.get(e.getValue()[0]);
            if (ctx == null) continue;
            byLen.computeIfAbsent(e.getKey().length(), k -> new ArrayList<>())
                    .add(e.getKey() + " " + ctx);
        }
        StringBuilder cf = new StringBuilder();
        for (List<String> l : byLen.values())
            for (String s : l) cf.append(s).append('\n');
        Io.writeFile(new File(cfgDir, "img_contexts"), cf.toString().getBytes());
        p.log("已导出 fs_config/SELinux 上下文 (" + tree.size() + " 项) → " + cfgDir.getAbsolutePath());
    }

    private static File pickCfg(File cfgDir, String label, String suffix) {
        if (label != null && !label.isEmpty()) {
            File f = new File(cfgDir, label + suffix);
            if (f.isFile()) return f;
        }
        File[] list = cfgDir.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isFile() && f.getName().endsWith(suffix)) return f;
            }
        }
        return null;
    }

    /** 打包后: 用 debugfs 把 fs_config 的 uid/gid 与 contexts 的 selinux 上下文写回镜像(属主/上下文还原)。 */
    private static void applyConfig(File img, File srcDir, String label, ToolPaths tools, Progress p)
            throws IOException {
        File cfgDir = null;
        for (File d : new File[]{srcDir, srcDir.getParentFile()}) {
            if (d == null) continue;
            File c = new File(d, "config");
            if (c.isDirectory()) { cfgDir = c; break; }
        }
        File fsCfg = null, ctxFile = null;
        if (cfgDir != null) {
            fsCfg = pickCfg(cfgDir, label, "_fs_config");
            ctxFile = pickCfg(cfgDir, label, "_contexts");
            if (fsCfg != null && ctxFile == null) {   // 与 fs_config 同名前缀找 contexts
                String base = fsCfg.getName().replace("_fs_config", "");
                File c2 = new File(cfgDir, base + "_contexts");
                if (c2.isFile()) ctxFile = c2;
            }
        }
        if ((fsCfg == null || !fsCfg.isFile()) && (ctxFile == null || !ctxFile.isFile())) {
            p.log("提示: 未找到 fs_config/上下文, 重打包镜像的属主/上下文保持默认(建议先解包再打包)");
            return;
        }
        p.log("应用 fs_config 与 SELinux 上下文 ...");
        List<String> scriptLines = new ArrayList<>();
        Pattern ws = Pattern.compile("\\s+");
        if (fsCfg != null && fsCfg.isFile()) {
            for (String line : new String(java.nio.file.Files.readAllBytes(fsCfg.toPath()),
                    "UTF-8").split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] f = ws.split(t);
                if (f.length < 4) continue;
                if (f[0].contains(" ") || f[0].contains("\"") || f[0].contains("'")) continue;
                scriptLines.add("set_inode_field " + f[0] + " uid " + f[1]);
                scriptLines.add("set_inode_field " + f[0] + " gid " + f[2]);
            }
        }
        if (ctxFile != null && ctxFile.isFile()) {
            for (String line : new String(java.nio.file.Files.readAllBytes(ctxFile.toPath()),
                    "UTF-8").split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int sp = t.indexOf(' ');
                if (sp <= 0) continue;
                String path = t.substring(0, sp);
                String ctx = t.substring(sp + 1).trim();
                if (ctx.isEmpty() || path.contains(" ") || path.contains("\"") || path.contains("'")) continue;
                scriptLines.add("ea_set " + path + " security.selinux " + ctx);
            }
        }
        if (scriptLines.isEmpty()) {
            p.log("提示: 配置为空, 跳过权限应用");
            return;
        }
        File script = File.createTempFile("dbgapp", ".cmds");
        script.deleteOnExit();
        StringBuilder sb = new StringBuilder();
        for (String s : scriptLines) sb.append(s).append('\n');
        Io.writeFile(script, sb.toString().getBytes());
        int code = Exec.run(tools.libDir, p, Exec.cmd(
                tools.debugfs.getAbsolutePath(), "-w", "-f", script.getAbsolutePath(),
                img.getAbsolutePath()), line ->
                !line.startsWith("debugfs:") && !line.contains("(6-Mar-2025)")
                        && !line.startsWith("debugfs "));
        if (code != 0) p.log("警告: 应用权限/上下文失败 (exit " + code + "), 镜像权限可能不完整");
    }
}