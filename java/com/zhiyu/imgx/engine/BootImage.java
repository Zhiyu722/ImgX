package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * boot / recovery 镜像解包与打包 —— 基于 magiskboot(来自 TIK5)。
 * magiskboot 正确处理所有版本(v0-v4)的头部、AVB 签名、多压缩格式。
 * 移除旧的纯 Java 解析(不完整/易出错)。
 */
public final class BootImage {

    private BootImage() {}

    /** 解包 boot 镜像: 调用 magiskboot unpack + 解压 ramdisk */
    public static void unpack(File img, File outDir, ToolPaths tools, Progress p) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建输出目录: " + outDir);

        p.log("调用 magiskboot unpack ...");
        List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                "unpack", "-h", img.getAbsolutePath());
        // 在 outDir 中运行: magiskboot 输出直接落在 outDir, 不污染镜像所在目录
        runInDir(cmd, outDir, tools.libDir, p);
        // 记录原始 boot 镜像路径, 供 repack 使用(magiskboot repack 需要原始镜像取头部)
        try {
            Io.writeFile(new File(outDir, "__orig_boot.txt"), img.getAbsolutePath().getBytes());
        } catch (Exception ignored) {}
        // 记录 ramdisk cpio 每个条目的原始元数据(权限/属主/软链), 打包时据此校验, 保证改动不出错
        try {
            File cpioFile = new File(outDir, "ramdisk.cpio");
            if (cpioFile.exists()) {
                java.util.List<CpioMeta.Entry> metas = CpioMeta.read(cpioFile);
                CpioMeta.saveManifest(metas, new File(outDir, "__ramdisk_meta.txt"),
                        new File(outDir, "ramdisk"));
                p.log("已记录 ramdisk 元数据: " + metas.size() + " 个条目(权限/属主将被保留)");
                int special = 0;
                for (CpioMeta.Entry e : metas) if (e.type == 3) special++;
                if (special > 0) p.log("  含 " + special + " 个特殊条目(设备节点等, 将原样保留)");
            }
        } catch (Exception ignored) {}

        // 解压 ramdisk
        File ramdiskDir = new File(outDir, "ramdisk");
        File ramdiskComp = null;
        for (String name : new String[]{"ramdisk.cpio", "ramdisk.cpio.lz4", "ramdisk.cpio.gz"}) {
            File f = new File(outDir, name);
            if (f.exists() && f.length() > 0) { ramdiskComp = f; break; }
        }
        if (ramdiskComp != null && ramdiskComp.length() > 0) {
            if (ramdiskComp.getName().equals("ramdisk.cpio")) {
                p.log("ramdisk: cpio 解包...");
                ramdiskDir.mkdirs();
                extractCpio(ramdiskComp, ramdiskDir, tools, p);
            } else {
                p.log("ramdisk: " + ramdiskComp.getName() + " → 解压中...");
                File decomp = new File(outDir, "ramdisk.cpio.decomp");
                List<String> decompCmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                        "decompress", ramdiskComp.getAbsolutePath(), decomp.getAbsolutePath());
                int rc = Exec.run(tools.libDir, p, decompCmd);
                if (rc == 0 && decomp.exists() && decomp.length() > 0) {
                    ramdiskDir.mkdirs();
                    extractCpio(decomp, ramdiskDir, tools, p);
                    decomp.delete();
                } else {
                    p.log("ramdisk: 解压失败, 保留原始文件");
                }
            }
        }
        p.log("boot 解包完成 ✓");
    }

    private static void extractCpio(File cpio, File dir, ToolPaths tools, Progress p) throws IOException {
        List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                "cpio", cpio.getAbsolutePath(), "extract");
        runInDir(cmd, dir, tools.libDir, p);
    }

    /** 打包 boot 镜像: 从解包目录还原(magiskboot repack)。自动查找含 kernel 的子目录。 */
    public static void pack(File bootDir, File outImg, ToolPaths tools, Progress p) throws IOException {
        if (!bootDir.isDirectory()) throw new IOException("boot 目录无效: " + bootDir);
        File realDir = findBootDir(bootDir);
        if (realDir == null) {
            throw new IOException("未找到 kernel 文件: 请选择解包 boot 后生成的目录"
                    + "(内含 kernel、ramdisk 等文件)");
        }
        if (realDir != bootDir) {
            p.log("自动定位到解包目录: " + realDir.getAbsolutePath());
        }
        File kernel = new File(realDir, "kernel");
        if (!kernel.exists()) throw new IOException("缺少 kernel 文件");

        // 还原 ramdisk 目录 → cpio(增量更新, 完整保留权限/属主/软链接)
        File ramdiskDir = new File(realDir, "ramdisk");
        File ramdiskCpio = new File(realDir, "ramdisk.cpio");
        if (ramdiskDir.isDirectory() && ramdiskCpio.exists()) {
            syncRamdiskDir(ramdiskDir, ramdiskCpio, tools, p);
        }

        // 用 magiskboot repack 生成最终 boot
        p.log("调用 magiskboot repack ...");
        File origBoot = readOrigBoot(realDir);
        if (origBoot != null && origBoot.isFile()) {
            p.log("使用原镜像头部: " + origBoot.getName());
            List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                    "repack", "-n", origBoot.getAbsolutePath(), outImg.getAbsolutePath());
            int rc = runInDirGetCode(cmd, realDir, tools.libDir, p);
            if (rc != 0) throw new IOException("magiskboot repack 失败 (exit " + rc + ")");
        } else {
            p.log("未找到原始 boot 镜像, 使用目录方式 repack ...");
            List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                    "repack", "-n", realDir.getAbsolutePath(), outImg.getAbsolutePath());
            int rc = Exec.run(tools.libDir, p, cmd);
            if (rc != 0) throw new IOException("magiskboot repack 失败 (exit " + rc + ")");
        }

        File newBoot = new File(realDir, "new-boot.img");
        if (newBoot.exists()) {
            newBoot.renameTo(outImg);
        }
        p.log("boot 打包完成 → " + outImg.getAbsolutePath());
    }

    /**
     * 把 ramdisk/ 目录的改动同步回原 ramdisk.cpio。
     * 以原 cpio 为基准做增量更新: 未改动的条目元数据(权限/属主/软链接)完全保留,
     * 只对改动/新增/删除的条目执行 magiskboot cpio 命令 —— 这样 init 等关键文件的
     * 权限不会因打包而丢失(否则会导致无法 init)。
     */
    private static void syncRamdiskDir(File ramdiskDir, File cpio, ToolPaths tools, Progress p)
            throws IOException {
        p.log("同步 ramdisk 改动到 cpio(保留权限/属主)...");
        File origDir = new File(ramdiskDir.getParentFile(), ".ramdisk_orig");
        Io.deleteRecursive(origDir);
        if (!origDir.mkdirs()) throw new IOException("无法创建临时目录: " + origDir);

        // 1) 把原 cpio 解到临时目录(magiskboot extract 会还原文件权限)
        //    含设备节点等无法创建的特殊条目时 extract 可能返回非0, 这里容忍(已解出的部分足够比对)
        try {
            runInDir(Exec.cmd(tools.magiskboot.getAbsolutePath(),
                    "cpio", cpio.getAbsolutePath(), "extract"), origDir, tools.libDir, p);
        } catch (IOException e) {
            p.log("提示: 部分特殊条目无法提取(设备节点等), 将原样保留在 cpio 中");
        }

        // 2) 比对差异, 生成 magiskboot cpio 命令
        List<String> cmds = new ArrayList<>();
        diffEntries(origDir, ramdiskDir, "", cmds, ramdiskDir);
        // 删除: 原 cpio 有但目录里已删掉的条目
        List<String> origList = listEntries(origDir, "");
        for (String e : origList) {
            File inNew = new File(ramdiskDir, e);
            if (!inNew.exists()) cmds.add("rm -r " + e);
        }

        if (cmds.isEmpty()) {
            p.log("ramdisk 未改动, 使用原 cpio(权限完全保持)");
        } else {
            p.log("应用 " + cmds.size() + " 项 ramdisk 改动 ...");
            List<String> cmd = Exec.cmd(tools.magiskboot.getAbsolutePath(),
                    "cpio", cpio.getAbsolutePath());
            cmd.addAll(cmds);
            runInDir(cmd, origDir, tools.libDir, p);
        }
        Io.deleteRecursive(origDir);
    }

    /** 递归比对目录差异, 生成 magiskboot cpio 命令(add/mkdir/ln)。 */
    private static void diffEntries(File origDir, File newDir, String prefix,
                                    List<String> cmds, File ramdiskRoot) {
        File[] items = newDir.listFiles();
        if (items == null) return;
        for (File f : items) {
            String entry = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            File orig = new File(origDir, entry);
            if (isSymlink(f)) {
                String target = readLink(f);
                String origTarget = isSymlink(orig) ? readLink(orig) : null;
                if (!isSymlink(orig) || (target != null && !target.equals(origTarget))) {
                    cmds.add("ln " + target + " " + entry);
                }
            } else if (f.isDirectory()) {
                if (!orig.isDirectory()) {
                    cmds.add("mkdir " + resolveMode(ramdiskRoot, entry, f) + " " + entry);
                }
                diffEntries(orig, f, entry, cmds, ramdiskRoot);
            } else {
                // 文件: 新增或内容变化时用 add 更新(权限由清单决定, 不受提取默认权限影响)
                if (!orig.isFile() || orig.length() != f.length() || !sameContent(orig, f)) {
                    cmds.add("add " + resolveMode(ramdiskRoot, entry, f) + " "
                            + entry + " " + f.getAbsolutePath());
                }
            }
        }
    }

    /** 列出目录下所有条目(相对路径)。 */
    private static List<String> listEntries(File dir, String prefix) {
        List<String> out = new ArrayList<>();
        File[] items = dir.listFiles();
        if (items == null) return out;
        for (File f : items) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            out.add(rel);
            if (f.isDirectory() && !isSymlink(f)) out.addAll(listEntries(f, rel));
        }
        return out;
    }

    private static boolean isSymlink(File f) {
        try { return f != null && f.exists() && java.nio.file.Files.isSymbolicLink(f.toPath()); }
        catch (Exception e) { return false; }
    }

    private static String readLink(File f) {
        try { return java.nio.file.Files.readSymbolicLink(f.toPath()).toString(); }
        catch (Exception e) { return null; }
    }

    /** 八进制权限字符串(如 0755)。 */
    private static String mode(File f) {
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
            return String.format("0%03o", m);
        } catch (Exception e) {
            return f.isDirectory() ? "0755" : "0644";
        }
    }

    private static boolean sameContent(File a, File b) {
        try {
            if (a.length() != b.length()) return false;
            byte[] ba = java.nio.file.Files.readAllBytes(a.toPath());
            byte[] bb = java.nio.file.Files.readAllBytes(b.toPath());
            return java.util.Arrays.equals(ba, bb);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 决定条目的权限(八进制字符串)。
     * 依据解包时记录的清单: 若用户没有手动改过权限(磁盘权限 == 解包后权限),
     * 则使用原 cpio 里的权限; 只有用户明确改过才用新权限。
     * 这样 magiskboot 提取时把权限写成默认值(如 666)也不会破坏打包结果。
     */
    private static String resolveMode(File ramdiskRoot, String entry, File cur) {
        int curMode = CpioMeta.modeOf(cur);
        try {
            File outDir = ramdiskRoot.getParentFile();
            File mf = new File(outDir, "__ramdisk_meta.txt");
            if (mf.exists()) {
                for (CpioMeta.Entry e : CpioMeta.loadManifest(mf)) {
                    if (!e.name.equals(entry)) continue;
                    boolean userChanged = e.extractedMode >= 0 && curMode >= 0
                            && (curMode & 07777) != (e.extractedMode & 07777);
                    if (!userChanged) return String.format("0%03o", e.mode);   // 原 cpio 权限
                    return String.format("0%03o", curMode);                    // 用户改过的权限
                }
            }
        } catch (Exception ignored) {}
        return String.format("0%03o", Math.max(0, curMode));   // 新文件: 用当前权限
    }

    /** 读取解包时记录的原镜像路径; 找不到返回 null。 */
    private static File readOrigBoot(File dir) {
        File f = new File(dir, "__orig_boot.txt");
        if (!f.exists()) return null;
        try {
            String p = new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
            if (!p.isEmpty()) return new File(p);
        } catch (Exception ignored) {}
        return null;
    }

    /** 在指定目录运行命令并返回退出码(不抛异常)。 */
    private static int runInDirGetCode(List<String> cmd, File dir, File libDir, Progress p) {
        try {
            runInDir(cmd, dir, libDir, p);
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 在指定目录或其一级子目录中查找含 kernel 的 boot 解包目录。 */
    private static File findBootDir(File dir) {
        if (new File(dir, "kernel").isFile()) return dir;
        File[] subs = dir.listFiles();
        if (subs != null) {
            for (File sub : subs) {
                if (sub.isDirectory() && new File(sub, "kernel").isFile()) return sub;
            }
        }
        return null;
    }

    private static void runInDir(List<String> cmd, File dir, File libDir, Progress p) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        if (libDir != null && libDir.isDirectory()) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                    existing == null || existing.isEmpty() ? libDir.getAbsolutePath()
                            : libDir.getAbsolutePath() + ":" + existing);
        }
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread pump = new Thread(() -> {
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) {
                    if (p != null && !line.trim().isEmpty()) p.log("  " + line.trim());
                }
            } catch (Exception ignored) {}
        });
        pump.setDaemon(true); pump.start();
        try {
            int code = proc.waitFor(); pump.join(2000);
            if (code != 0) throw new IOException("命令失败 (exit " + code + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断");
        }
    }
}