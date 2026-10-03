package com.zhiyu.imgx.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行外部进程(内置原生工具)的工具类。
 * 设置 LD_LIBRARY_PATH 指向捆绑的动态库目录, 使 termux 编译的二进制在 App 内可运行。
 *
 * 兼容性: 某些 ROM 对应用直接 exec 自家 files 目录下的二进制有限制(execve 返回 EACCES),
 * 此时自动回退到 /system/bin/sh -c 方式执行, 并保留 LD_LIBRARY_PATH。
 */
public final class Exec {

    private Exec() {}

    /** 执行命令, 实时回传 stdout/stderr 到 Progress.log, 返回退出码。 */
    public static int run(File libDir, Progress p, List<String> cmd) throws IOException {
        return run(libDir, p, cmd, null);
    }

    /** 执行命令, 可过滤不需要展示的输出行(如 chown 警告)。 */
    public static int run(File libDir, Progress p, List<String> cmd,
                          java.util.function.Predicate<String> lineFilter) throws IOException {
        try {
            return runInternal(libDir, p, cmd, lineFilter, false);
        } catch (IOException e) {
            // 直接 exec 被 ROM 拒绝(EACCES)时, 尝试通过 /system/bin/sh 执行
            if (e.getMessage() != null && e.getMessage().contains("error=13")) {
                try {
                    return runInternal(libDir, p, cmd, lineFilter, true);
                } catch (IOException e2) {
                    throw new IOException("直接执行与 shell 执行均失败: " + e.getMessage()
                            + " / " + e2.getMessage());
                }
            }
            throw e;
        }
    }

    private static int runInternal(File libDir, Progress p, List<String> cmd,
                                   java.util.function.Predicate<String> lineFilter,
                                   boolean viaShell) throws IOException {
        if (cmd.isEmpty()) throw new IOException("空命令");
        List<String> realCmd = cmd;
        if (viaShell) {
            // 通过 /system/bin/sh 执行, 规避部分 ROM 对 app exec 的限制
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cmd.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(quote(cmd.get(i)));
            }
            realCmd = new ArrayList<>();
            realCmd.add("/system/bin/sh");
            realCmd.add("-c");
            realCmd.add(sb.toString());
        }

        ProcessBuilder pb = newProc(cmd, libDir);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread pump = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (lineFilter != null && !lineFilter.test(t)) continue;
                    if (p != null) p.log("  " + t);
                }
            } catch (IOException ignored) {}
        });
        pump.setDaemon(true);
        pump.start();
        try {
            int code = proc.waitFor();
            pump.join(2000);
            return code;
        } catch (InterruptedException e) {
            proc.destroy();
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** 简单 shell 转义(处理路径含空格的情况)。 */
    private static String quote(String s) {
        if (s.indexOf(' ') < 0 && s.indexOf('\'') < 0 && s.indexOf('"') < 0) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static int runQuiet(File libDir, List<String> cmd) throws IOException {
        return run(libDir, null, cmd);
    }

    /** 构造带 LD_LIBRARY_PATH / MKE2FS_CONFIG 环境的 ProcessBuilder。 */
    private static ProcessBuilder newProc(List<String> realCmd, File libDir) {
        ProcessBuilder pb = new ProcessBuilder(realCmd);
        if (libDir != null && libDir.isDirectory()) {
            String existing = System.getenv("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                    existing == null || existing.isEmpty() ? libDir.getAbsolutePath()
                            : libDir.getAbsolutePath() + ":" + existing);
        }
        if (!realCmd.isEmpty()) {
            File exe = new File(realCmd.get(0));
            File conf = new File(exe.getParentFile(), "mke2fs.conf");
            if (exe.getName().equals("mke2fs") && conf.exists()) {
                pb.environment().put("MKE2FS_CONFIG", conf.getAbsolutePath());
            }
        }
        return pb;
    }

    /** 执行命令并完整返回 stdout+stderr 文本(不回传进度)。 */
    public static String capture(File libDir, List<String> cmd) throws IOException {
        try {
            return captureInternal(libDir, cmd, false);
        } catch (IOException e) {
            // 直接 exec 被 ROM 拒绝(EACCES)时, 尝试通过 /system/bin/sh 执行
            if (e.getMessage() != null && e.getMessage().contains("error=13")) {
                try {
                    return captureInternal(libDir, cmd, true);
                } catch (IOException e2) {
                    throw new IOException("直接执行与 shell 执行均失败: " + e.getMessage()
                            + " / " + e2.getMessage());
                }
            }
            throw e;
        }
    }

    private static String captureInternal(File libDir, List<String> cmd, boolean viaShell)
            throws IOException {
        if (cmd.isEmpty()) throw new IOException("空命令");
        List<String> realCmd = cmd;
        if (viaShell) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cmd.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(quote(cmd.get(i)));
            }
            realCmd = new ArrayList<>();
            realCmd.add("/system/bin/sh");
            realCmd.add("-c");
            realCmd.add(sb.toString());
        }
        ProcessBuilder pb = newProc(realCmd, libDir);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder sb = new StringBuilder();
        Thread pump = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                char[] buf = new char[8192];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            } catch (IOException ignored) {}
        });
        pump.setDaemon(true);
        pump.start();
        try {
            int code = proc.waitFor();
            pump.join(2000);
            if (code != 0) throw new IOException("命令退出码 " + code + ": " + cmd.get(0));
            return sb.toString();
        } catch (InterruptedException e) {
            proc.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断");
        }
    }

    public static List<String> cmd(String... parts) {
        List<String> l = new ArrayList<>();
        for (String s : parts) l.add(s);
        return l;
    }
}