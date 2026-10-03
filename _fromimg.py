#!/usr/bin/env python3
"""用 icon_source.jpg 生成 5 档图标: 内容包围盒裁剪 + 黑边变透明 + 主体居中。"""
from PIL import Image
import os
SRC = 'icon_source.jpg'

def content_bbox(im, thr=30, step=2):
    g = im.convert('L')
    w, h = g.size
    xs, ys = [], []
    for y in range(0, h, step):
        for x in range(0, w, step):
            if g.getpixel((x, y)) > thr:
                xs.append(x); ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)

def make(sz):
    im = Image.open(SRC).convert('RGB')
    x0, y0, x1, y1 = content_bbox(im)
    cw, ch = x1 - x0, y1 - y0
    side = max(cw, ch)
    canvas = Image.new('RGB', (side, side), (0, 0, 0))
    canvas.paste(im.crop((x0, y0, x1, y1)), ((side - cw) // 2, (side - ch) // 2))
    # 黑边变透明: 亮度<=20 全透明, >=35 不透明, 中间 15 级渐变抗锯齿
    g = canvas.convert('L')
    alpha = g.point(lambda v: 0 if v <= 20 else (255 if v >= 35 else int((v - 20) * 17)))
    rgba = canvas.convert('RGBA')
    rgba.putalpha(alpha)
    return rgba.resize((sz, sz), Image.LANCZOS)

for name, S in (("mipmap-mdpi-v4", 48), ("mipmap-hdpi-v4", 72), ("mipmap-xhdpi-v4", 96),
                ("mipmap-xxhdpi-v4", 144), ("mipmap-xxxhdpi-v4", 192)):
    out = os.path.join("res", name, "ic_launcher.png")
    make(S).save(out, "PNG", optimize=True)
    print("wrote", out, S)
