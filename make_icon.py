#!/usr/bin/env python3
"""生成 ImgX 液态玻璃图标(5 密度)。"""
from PIL import Image, ImageDraw, ImageFilter
import math, os

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def make_icon(S):
    SS = S * 4
    img = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    px = img.load()
    # 对角渐变背景(深蓝 → 亮蓝)
    c1, c2 = (10, 72, 196), (74, 176, 255)
    for y in range(SS):
        for x in range(SS):
            t = (x + y) / (2.0 * SS)
            px[x, y] = lerp(c1, c2, t) + (255,)
    img = img.filter(ImageFilter.GaussianBlur(SS * 0.01))
    d = ImageDraw.Draw(img, "RGBA")
    # 折射亮带(两条斜高光, 低透明度)
    for off, w, al in ((-0.10, 0.045, 70), (0.06, 0.025, 50)):
        cx = SS * (0.5 + off)
        w = SS * w
        pts = []
        for x in range(SS):
            y = (x - cx) * 0.62 + SS * 0.5
            pts.append((x, y))
        d.line(pts, fill=(255, 255, 255, al), width=int(w), joint="curve")
    # 顶部镜面高光(柔和的椭圆光斑)
    sh = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh, "RGBA")
    sd.ellipse([SS * 0.02, SS * 0.00, SS * 0.78, SS * 0.34], fill=(255, 255, 255, 60))
    sh = sh.filter(ImageFilter.GaussianBlur(SS * 0.06))
    img.alpha_composite(sh)
    # 大 X: 两条交叉圆头粗条(白色玻璃, 微渐变 + 投影)
    def bar(cx, cy, ang, length, width):
        angr = math.radians(ang)
        dx, dy = math.cos(angr), math.sin(angr)
        x0, y0 = cx - dx * length / 2, cy - dy * length / 2
        x1, y1 = cx + dx * length / 2, cy + dy * length / 2
        return [(x0, y0), (x1, y1)]
    cx = cy = SS * 0.5
    L, W = SS * 0.62, SS * 0.19
    # 先统一画两条投影(避免后画的投影压暗先画的条)
    shadow = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow, "RGBA")
    for ang in (45, -45):
        sd.line(bar(cx, cy + SS * 0.006, ang, L, W), fill=(6, 26, 66, 42), width=int(W), joint="curve")
    shadow = shadow.filter(ImageFilter.GaussianBlur(SS * 0.004))
    img.alpha_composite(shadow)
    # 白色玻璃条
    masks = []
    for ang in (45, -45):
        m2 = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        md = ImageDraw.Draw(m2, "RGBA")
        md.line(bar(cx, cy, ang, L, W), fill=(255, 255, 255, 255), width=int(W), joint="curve")
        img.alpha_composite(m2)
        masks.append(m2)
        # 竖渐变 + 顶部镜面细线
        for y in range(SS):
            t = y / SS
            col = lerp((255, 255, 255, 255), (176, 202, 246, 255), t)
            for x in range(SS):
                if m2.getpixel((x, y))[3] > 0:
                    img.putpixel((x, y), col)
        hl = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        hd = ImageDraw.Draw(hl, "RGBA")
        hd.line(bar(cx, cy, ang, L * 0.92, W * 0.4), fill=(255, 255, 255, 230), width=int(max(2, W * 0.05)), joint="curve")
        hl = hl.filter(ImageFilter.GaussianBlur(max(1, SS * 0.004)))
        img.alpha_composite(hl)
    # 整体柔和内投影收边
    img = img.filter(ImageFilter.GaussianBlur(SS * 0.003))
    return img.resize((S, S), Image.LANCZOS)

for name, S in (("mipmap-mdpi-v4", 48), ("mipmap-hdpi-v4", 72), ("mipmap-xhdpi-v4", 96),
                ("mipmap-xxhdpi-v4", 144), ("mipmap-xxxhdpi-v4", 192)):
    out = os.path.join("res", name, "ic_launcher.png")
    make_icon(S).save(out, "PNG")
    print("wrote", out, S)
