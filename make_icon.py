#!/usr/bin/env python3
"""生成 ImgX 液态玻璃图标(5 密度): 深蓝紫渐变 + 粗圆头白色 X。"""
from PIL import Image, ImageDraw, ImageFilter
import math, os

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def make_icon(S):
    SS = S * 4
    img = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    px = img.load()
    # 对角渐变背景: 深蓝(左上) -> 紫(右下)
    c1, c2 = (18, 84, 214), (124, 58, 236)
    for y in range(SS):
        for x in range(SS):
            t = (x + y) / (2.0 * SS)
            px[x, y] = lerp(c1, c2, t) + (255,)
    img = img.filter(ImageFilter.GaussianBlur(SS * 0.01))
    d = ImageDraw.Draw(img, "RGBA")
    # 微弱的大光斑(左上高光), 增加玻璃感但不抢
    sh = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh, "RGBA")
    sd.ellipse([-SS * 0.15, -SS * 0.12, SS * 0.62, SS * 0.34], fill=(255, 255, 255, 46))
    sh = sh.filter(ImageFilter.GaussianBlur(SS * 0.09))
    img.alpha_composite(sh)

    # 粗圆头 X
    cx = cy = SS * 0.5
    L, W = SS * 0.66, SS * 0.21
    r = W / 2
    # 先画两条投影(轻、贴、小模糊)
    shadow = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow, "RGBA")
    for ang in (45, -45):
        a = math.radians(ang)
        x0, y0 = cx - math.cos(a) * L / 2, cy - math.sin(a) * L / 2 + SS * 0.008
        x1, y1 = cx + math.cos(a) * L / 2, cy + math.sin(a) * L / 2 + SS * 0.008
        sd.line([(x0, y0), (x1, y1)], fill=(10, 20, 70, 70), width=int(W))
        sd.ellipse([x0 - r, y0 - r, x0 + r, y0 + r], fill=(10, 20, 70, 70))
        sd.ellipse([x1 - r, y1 - r, x1 + r, y1 + r], fill=(10, 20, 70, 70))
    shadow = shadow.filter(ImageFilter.GaussianBlur(SS * 0.007))
    img.alpha_composite(shadow)
    # 白色 X(纯白, 干净)
    for ang in (45, -45):
        a = math.radians(ang)
        x0, y0 = cx - math.cos(a) * L / 2, cy - math.sin(a) * L / 2
        x1, y1 = cx + math.cos(a) * L / 2, cy + math.sin(a) * L / 2
        d.line([(x0, y0), (x1, y1)], fill=(255, 255, 255, 255), width=int(W))
        d.ellipse([x0 - r, y0 - r, x0 + r, y0 + r], fill=(255, 255, 255, 255))
        d.ellipse([x1 - r, y1 - r, x1 + r, y1 + r], fill=(255, 255, 255, 255))
        # 每条臂顶部一条细亮线(镜面高光), 轻微向下偏移
        hl = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        hd = ImageDraw.Draw(hl, "RGBA")
        ox, oy = -math.sin(a) * W * 0.30, math.cos(a) * W * 0.30
        hx0, hy0 = x0 + ox, y0 + oy
        hx1, hy1 = x1 + ox, y1 + oy
        hd.line([(hx0, hy0), (hx1, hy1)], fill=(255, 255, 255, 205), width=int(max(2, W * 0.07)), joint="curve")
        hl = hl.filter(ImageFilter.GaussianBlur(max(1, SS * 0.004)))
        img.alpha_composite(hl)
    # 中心交叉处再提亮一档(两条臂叠加)
    core = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    cd = ImageDraw.Draw(core, "RGBA")
    cd.ellipse([cx - W * 0.5, cy - W * 0.5, cx + W * 0.5, cy + W * 0.5], fill=(255, 255, 255, 255))
    img.alpha_composite(core)
    return img.resize((S, S), Image.LANCZOS)

if __name__ == "__main__":
    for name, S in (("mipmap-mdpi-v4", 48), ("mipmap-hdpi-v4", 72), ("mipmap-xhdpi-v4", 96),
                    ("mipmap-xxhdpi-v4", 144), ("mipmap-xxxhdpi-v4", 192)):
        out = os.path.join("res", name, "ic_launcher.png")
        make_icon(S).save(out, "PNG")
        print("wrote", out, S)
