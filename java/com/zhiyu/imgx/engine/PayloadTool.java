package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** payload.bin 解包 —— 调用内置 payload-dumper-go。 */
public final class PayloadTool {

    private PayloadTool() {}

    /** 解包 payload.bin 所有分区到 outDir。 */
    public static void extract(File payload, File outDir, ToolPaths tools, Progress p) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建目录: " + outDir);
        p.log("调用 payload-dumper-go 提取分区 ...");
        List<String> cmd = Exec.cmd(tools.payloadDumper.getAbsolutePath(),
                "-o", outDir.getAbsolutePath(),
                payload.getAbsolutePath());
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("payload 解包失败 (exit " + code + ")");
        p.log("payload 分区提取完成 → " + outDir.getAbsolutePath());
    }
}
