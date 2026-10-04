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
        // 工作目录(输出同级, /sdcard 普通用户可写): 放 App 配置副本 + 转换产物, 用完删除
        File workDir = new File(outImg.getParentFile() != null ? outImg.getParentFile()
                        : new File(System.getProperty("java.io.tmpdir")),
                ".imgx_mke_" + System.currentTimeMillis());
        if (!workDir.exists() && !workDir.mkdirs()) throw new IOException("无法创建临时目录");

        // 1. 配置预读: 定位源目录里的 App 配置(config/ 或 ImgX/config/), 复制到工作目录
        //    (config 目录随后要被移走, 必须在移走前把打包要用的 fs_config/contexts 备份出来)
        File cfgDir = pickCfgDir(srcDir, label);
        File fsCfg = cfgDir != null ? pickCfg(cfgDir, label, "_fs_config") : null;
        File ctxCfg = cfgDir != null ? pickCfg(cfgDir, label, "_contexts") : null;
        File mkeFs = null, mkeCtx = null;
        if (fsCfg != null && ctxCfg != null) {
            mkeFs = convertFsConfig(fsCfg, workDir, srcDir);
            mkeCtx = convertContexts(ctxCfg, workDir, srcDir);
        }

        // 2. 移走源目录内 App 生成物(不进入镜像): .imgx_mke 残留 / config / ImgX 配置目录 / __image_size.txt
        java.util.List<File> stashed = new ArrayList<>();
        java.util.List<File> candidates = new java.util.ArrayList<>();
        File imgxDir = new File(srcDir, "ImgX");
        if (imgxDir.isDirectory() && (new File(imgxDir, "__image_size.txt").exists()
                || new File(imgxDir, "config").isDirectory())) candidates.add(imgxDir);
        candidates.add(new File(srcDir, "config"));
        candidates.add(new File(srcDir, "__image_size.txt"));
        candidates.add(new File(srcDir, ".imgx_mke"));
        File stashParent = (srcDir.getParentFile() != null) ? srcDir.getParentFile()
                : workDir;
        for (File cand : candidates) {
            if (!cand.exists()) continue;
            File dst = new File(stashParent, "." + cand.getName() + "_stash_" + System.currentTimeMillis());
            if (cand.renameTo(dst)) {
                stashed.add(dst);
                p.log("已移走 App 生成目录 " + cand.getName() + "(不入镜像, 打包后还原)");
            }
        }

        try {
            packInner(srcDir, outImg, label, tools, p, mkeFs, mkeCtx, workDir);
        } finally {
            // 3. 还原源目录 + 清理工作目录
            for (File dst : stashed) {
                File back = new File(dst.getParentFile(), dst.getName()
                        .replaceFirst("^\\." + "", "").replaceFirst("_stash_\\d+$", ""));
                if (!back.exists()) dst.renameTo(back);
            }
            Io.deleteRecursive(workDir);
        }
    }

    /** TIK 式自动容量: 内容越小余量比例越高(照搬 TIK run.py rsize 阶梯算法) */
    private static long tikCalcSize(long size) {
        if (size <= 2097152) return 2097152;          // 极小内容: 保底 2MB
        long size_ = size + 10086;                    // 恒加 10086 字节
        double bs;
        if (size_ > 2684354560L) bs = 1.0658;         // > 2.5GB
        else if (size_ > 1073741824L) bs = 1.0858;    // 1~2.5GB
        else if (size_ > 536870912L) bs = 1.0958;     // 500MB~1GB
        else if (size_ > 104857600L) bs = 1.1158;     // 100~500MB
        else bs = 1.1258;                             // ≤ 100MB
        return (long) (size_ * bs);
    }

    /** 定位打包配置目录: 源目录/config → 源目录/ImgX/config → 源目录父级/config → null */
    private static File pickCfgDir(File srcDir, String label) {        File[] cands = {
                new File(srcDir, "config"),
                new File(new File(srcDir, "ImgX"), "config"),
        };
        for (File c : cands) {
            if (c.isDirectory()) return c;
        }
        if (srcDir.getParentFile() != null) {
            File par = new File(srcDir.getParentFile(), "config");
            if (par.isDirectory()) return par;
        }
        return null;
    }

    private static void packInner(File srcDir, File outImg, String label, ToolPaths tools, Progress p,
                                  File mkeFs, File mkeCtx, File workDir) throws IOException {
        long fileSize = Io.filesSize(srcDir);
        if (fileSize == 0) throw new IOException("源目录为空");
        // TIK 自动容量算法: 按内容大小分级余量(内容越小余量比例越高), 恒加 10086 字节
        //   内容 ≤100MB→1.1258x  ≤500MB→1.1158x  ≤1GB→1.0958x  ≤2.5GB→1.0858x  >2.5GB→1.0658x
        //   内容极少时保底 2MB。这样"只改 1MB"的镜像不会虚大, 加了很多东西也能自动按比例扩容。
        long size = tikCalcSize(fileSize);
        size = (size + 4095) / 4096 * 4096;
        if (size < (64L << 20)) size = 64L << 20;   // make_ext4fs 最小可用容量

        p.log("计算大小: 文件 " + (fileSize / 1048576) + " MB → 镜像 " + (size / 1048576) + " MB (TIK 自动容量)");

        // 优先: TIK 式 make_ext4fs 打包(制盘时直接写入 属主/权限/SELinux, 打包完成即可刷机)
        // 自动扩容兜底: 容量不足(如加了很多东西)时按 ×1.25 逐级扩容重试, 最多 5 次/上限 4GB
        for (int attempt = 0; attempt < 6; attempt++) {
            boolean spaceErr = false;
            if (tools.makeExt4fs != null && tools.makeExt4fs.isFile()) {
                try {
                    packMakeExt4fs(srcDir, outImg, label, size, tools, p, mkeFs, mkeCtx);
                    p.log("ext4 镜像已生成(权限已内嵌) → " + outImg.getAbsolutePath());
                    return;
                } catch (Exception e) {
                    spaceErr = isSpaceError(e.getMessage());
                    if (spaceErr) {
                        p.log("容量不足: " + e.getMessage());
                    } else {
                        p.log("make_ext4fs 打包失败: " + e.getMessage() + ", 回退 mke2fs+debugfs");
                    }
                    if (outImg.exists() && !outImg.delete()) outImg.deleteOnExit();
                }
            }
            if (!spaceErr) {
                // mke2fs 回退(同样带自动扩容)
                try {
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
                    return;
                } catch (Exception e) {
                    if (isSpaceError(e.getMessage()) && attempt < 5) {
                        spaceErr = true;
                    } else {
                        throw e instanceof IOException ? (IOException) e
                                : new IOException("打包失败: " + e.getMessage());
                    }
                }
            }
            if (!spaceErr) break;   // 非空间错误: 停止重试
            size = (size * 5 / 4 + 4095) / 4096 * 4096;      // 扩容 ×1.25
            if (size > (4L << 30)) size = 4L << 30;          // 上限 4GB
            p.log("自动扩容 → " + (size / 1048576) + " MB 重试");
        }
        throw new IOException("打包失败: 多次扩容后仍空间不足(源目录可能过大)");
    }

    /** 是否空间不足类错误(容量不够, 需要扩容重试) */
    private static boolean isSpaceError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("no space") || m.contains("not enough") || m.contains("enospc")
                || m.contains("space left") || m.contains("too small") || m.contains("larger than")
                || m.contains("exceeds") || m.contains("extents") || m.contains("capacity");
    }

    // ==================== TIK 式打包: make_ext4fs(权限内嵌, 可直刷) ====================

    /** make_ext4fs -S file_contexts -C fs_config 打包, uid/gid/mode/SELinux 全部写入镜像。
     *  mkeFs/mkeCtx 由 pack() 预转换好传入(源目录 config 已被移走, 不能再现找)。 */
    private static void packMakeExt4fs(File srcDir, File outImg, String label, long size,
                                       ToolPaths tools, Progress p,
                                       File mkeFs, File mkeCtx) throws IOException {
        if (mkeFs == null || mkeCtx == null)
            throw new IOException("缺少 config/fs_config 或 contexts(请先解包生成配置)");
        p.log("调用 make_ext4fs 生成 ext4(含属主/权限/SELinux) ...");
        List<String> cmd = Exec.cmd(tools.makeExt4fs.getAbsolutePath(),
                "-J", "-T", "0",      // -J 带日志(对齐 TIK 命令)
                "-S", mkeCtx.getAbsolutePath(),
                "-l", String.valueOf(size),
                "-C", mkeFs.getAbsolutePath(),
                "-L", label == null || label.isEmpty() ? "ImgX" : label,
                "-a", label == null || label.isEmpty() ? "ImgX" : label,
                outImg.getAbsolutePath(), srcDir.getAbsolutePath());
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("make_ext4fs 失败 (exit " + code + ")");
    }

    /** img_fs_config(/path uid gid 0mode 0) → make_ext4fs 格式: 路径去前导 /, 根与 lost+found 保留。
     *  照搬 TIK fspatch: 扫描源目录, 为 config 缺失的路径补默认条目 —— 保证 make_ext4fs
     *  不会因 "not found in canned fs_config" 失败(不再需要手工排除目录)。 */
    private static File convertFsConfig(File in, File tmpDir, File srcDir) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String ln : new String(java.nio.file.Files.readAllBytes(in.toPath()))
                .split("\n")) {
            ln = ln.trim();
            if (ln.isEmpty()) continue;
            String[] f = ln.split("\\s+");
            if (f.length < 5) continue;
            map.put(f[0], f[1] + " " + f[2] + " " + f[3] + " " + f[4]);
        }
        // TIK fspatch 等价: 源目录每个文件/目录都必须有条目, 缺失的补默认值
        java.util.Set<String> dirs = new java.util.HashSet<>();
        java.util.Set<String> files = new java.util.HashSet<>();
        collectPaths(srcDir, "", dirs, files);
        for (String d : dirs) if (!map.containsKey(d)) map.put(d, "0 0 0755 0");
        for (String f : files) if (!map.containsKey(f)) map.put(f, "0 0 0644 0");

        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            String path = e.getKey(), rest = e.getValue();
            if ("/".equals(path)) {
                out.add("/ " + rest);
            } else if (path.startsWith("/")) {
                out.add(path.substring(1) + " " + rest);
            } else {
                out.add(path + " " + rest);
            }
        }
        if (!map.containsKey("/")) out.add("/ 0 0 0755 0");
        if (!map.containsKey("/lost+found")) out.add("/lost+found 0 0 0700 0");
        out.sort(String::compareTo);   // '/' 排最前; 其余按路径序
        File outF = new File(tmpDir, "mke_fs_config");
        Io.writeFile(outF, String.join("\n", out).getBytes());
        return outF;
    }

    /** 递归收集源目录所有目录/文件路径(带前导 /), 供 fspatch/contextpatch 补全缺失条目 */
    private static void collectPaths(File dir, String prefix,
                                     java.util.Set<String> dirs, java.util.Set<String> files) {
        if ("".equals(prefix)) {
            dirs.add("/");
            prefix = "/" + dir.getName();
            dirs.add(prefix);
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            String p = prefix + "/" + c.getName();
            if (c.isDirectory()) {
                dirs.add(p);
                collectPaths(c, p, dirs, files);
            } else {
                files.add(p);
            }
        }
    }

    /** img_contexts(/path ctx) → make_ext4fs -S 格式: 正则特殊字符转义 + 目录继承行。
     *  照搬 TIK contextpatch: 源目录缺失上下文的路径补默认(根上下文) —— 杜绝
     *  "cannot lookup security context" 失败。 */
    private static File convertContexts(File in, File tmpDir, File srcDir) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        String rootCtx = null;
        for (String ln : new String(java.nio.file.Files.readAllBytes(in.toPath()))
                .split("\n")) {
            ln = ln.trim();
            if (ln.isEmpty()) continue;
            String[] f = ln.split("\\s+", 2);
            if (f.length < 2) continue;
            if ("/".equals(f[0])) rootCtx = f[1];
            map.put(f[0], f[1]);
        }
        if (rootCtx == null) rootCtx = "u:object_r:system_file:s0";
        // TIK contextpatch 等价: 源目录所有路径都必须有上下文
        java.util.Set<String> allDirs = new java.util.HashSet<>();
        java.util.Set<String> allFiles = new java.util.HashSet<>();
        collectPaths(srcDir, "", allDirs, allFiles);
        for (String d : allDirs) if (!map.containsKey(d)) map.put(d, rootCtx);
        for (String f : allFiles) if (!map.containsKey(f)) map.put(f, rootCtx);
        // 目录 = 有其他条目以此为前缀
        java.util.Set<String> dirs = new java.util.HashSet<>();
        for (String k : map.keySet()) {
            if ("/".equals(k)) continue;
            int cut = k.lastIndexOf('/');
            if (cut > 0) dirs.add(k.substring(0, cut));
        }
        List<String> out = new ArrayList<>();
        out.add("/ " + rootCtx);
        List<String> keys = new ArrayList<>(map.keySet());
        keys.remove("/");
        keys.sort((a, b) -> {
            int c = Integer.compare(a.length(), b.length());   // 父目录(短)在前
            return c != 0 ? c : a.compareTo(b);
        });
        for (String k : keys) {
            String ctx = map.get(k);
            String esc = escapeRegex(k);
            out.add(esc + " " + ctx);
            if (dirs.contains(k)) out.add(esc + "(/.*)? " + ctx);
        }
        File outF = new File(tmpDir, "mke_file_contexts");
        Io.writeFile(outF, String.join("\n", out).getBytes());
        return outF;
    }

    /** file_contexts 中正则特殊字符转义(lost+found → lost\\+found 等)。 */
    private static String escapeRegex(String path) {
        StringBuilder sb = new StringBuilder(path.length() + 8);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if ("\\^$.|?*+(){}[]".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
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