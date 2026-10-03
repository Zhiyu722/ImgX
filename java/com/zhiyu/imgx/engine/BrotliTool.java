package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** brotli 压缩/解压 —— 调用内置 brotli CLI。 */
public final class BrotliTool {

    private BrotliTool() {}

    public static void decompress(File br, File out, ToolPaths tools, Progress p) throws IOException {
        p.log("brotli 解压 " + br.getName() + " ...");
        List<String> cmd = Exec.cmd(tools.brotli.getAbsolutePath(),
                "-d", br.getAbsolutePath(), "-o", out.getAbsolutePath());
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("brotli 解压失败 (exit " + code + ")");
    }

    public static void compress(File in, File br, int level, ToolPaths tools, Progress p) throws IOException {
        List<String> cmd = Exec.cmd(tools.brotli.getAbsolutePath(),
                "-" + level, "-f", in.getAbsolutePath(), "-o", br.getAbsolutePath());
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("brotli 压缩失败 (exit " + code + ")");
    }
}
