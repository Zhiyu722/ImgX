# ImgX(免 root 固件镜像工具)

免 root 的 Android 固件镜像解包/打包工具。核心逻辑移植自 [ColdWindScholar/D.N.A3](https://github.com/ColdWindScholar/D.N.A3)(MIT) 与 [hanfume/TIK5](https://github.com/hanfume/TIK5),UI 为液态玻璃(Liquid Glass)风格,带弹簧物理滑动效果。

- 包名:`com.zhiyu.imgx`
- 作者:Zhiyu · cuoxianxu
- 无需 root:解包只是读文件;只有"从手机物理分区导出镜像"才需要 root,本应用不包含该功能

## 功能

| 方向 | 格式 |
| ---- | ---- |
| 解包 | ext2/3/4 `*.img`、Android sparse 镜像、`super.img`(liblp 动态分区)、`payload.bin`(OTA)、`boot.img`(含 ramdisk)、`erofs`、`*.new.dat`(+`.dat.br`、分段 `.dat.1~N`)、`*.zip`(自动识别内部 payload/.dat/.img)、`*.br`、`*.gz`、`*.lz4` |
| 打包 | 目录 → ext4 镜像(含 fs_config/SELinux 上下文)、raw → sparse 镜像、解包目录 → boot.img(通过 magiskboot) |

解包路径可自定义(默认 `/sdcard/ImgX/out`),支持 Android 11+「所有文件访问」与 SAF 文件选择器。

## 原理(移植自 D.N.A3 + TIK5)

1. **魔数识别**:不靠扩展名,直接读文件头判断 zip / sparse / ext4 / super / payload / boot 等格式。
2. **解包**:调用内置二进制(magiskboot / debugfs / lpmake / extract.erofs / payload-dumper-go 等)提取文件系统到目录。
3. **打包**:`mke2fs` + `debugfs` 写回;重打包时用解包导出的 `config/img_fs_config` 与 `config/img_contexts` 还原每文件 uid/gid/权限与 SELinux 上下文(无 root 环境无需 e2fsdroid)。
4. **UI**:顶栏玻璃分段控件(内容自适应宽度) + 弹簧分页器,纯自绘液态玻璃效果(C 语言软件光栅化,失败回退 Java 绘制)。

## 构建

```bash
bash build.sh   # aapt2 + javac + d8 + 自定义打包(原生库 4K 对齐) + apksigner
```

图标可用 `python3 make_icon.py` 重新生成(液态玻璃风格, 5 种密度)。

## 说明

- 纯本地工具链,不上传任何文件。
- boot.img 的 ramdisk 权限通过 `__ramdisk_meta.txt` 元数据完整保留。
