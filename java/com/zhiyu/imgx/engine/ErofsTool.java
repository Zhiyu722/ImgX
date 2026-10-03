package com.zhiyu.imgx.engine;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** erofs 镜像解包 —— 调用内置 dump.erofs。 */
public final class ErofsTool {

    private ErofsTool() {}

    /** 解包 erofs 镜像到 outDir(extract.erofs 来自 TIK5, 完整保留目录结构)。 */
    public static void extract(File img, File outDir, ToolPaths tools, Progress p) throws IOException {
        if (!tools.extractErofs.exists()) throw new IOException("缺少 extract.erofs 工具");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建目录: " + outDir);
        p.log("调用 extract.erofs 提取 erofs 文件系统 ...");
        List<String> cmd = Exec.cmd(tools.extractErofs.getAbsolutePath(),
                "-x", outDir.getAbsolutePath(),
                img.getAbsolutePath());
        int code = Exec.run(tools.libDir, p, cmd);
        if (code != 0) throw new IOException("extract.erofs 提取失败 (exit " + code + ")");
        p.log("erofs 提取完成 → " + outDir.getAbsolutePath());
    }
}
