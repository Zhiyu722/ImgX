#!/usr/bin/env python3
"""生成 ImgX 霓虹炫酷图标(5 密度): 暗夜底 + 青→紫渐变炫光 X + 射线 + 星光。"""
from PIL import Image, ImageDraw, ImageFilter
import math, os

def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def star(d, cx, cy, R1, R2, w, fill):
    """4 角星光"""
    pts = []
    for i in range(8):
        ang = math.pi / 4 * i
        rad = R1 if i % 2 == 0 else R2
        pts.append((cx + math.cos(ang) * rad, cy + math.sin(ang) * rad))
    d.polygon(pts, fill=fill)
    d.ellipse([cx - w, cy - w, cx + w, cy + w], fill=fill)

def make_icon(S):
    SS = S * 4
    img = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    px = img.load()
    # 暗夜对角渐变: 深蓝黑(左上) → 深紫(右下)
    c1, c2 = (8, 12, 42), (34, 16, 74)
    for y in range(SS):
        for x in range(SS):
            t = (x + y) / (2.0 * SS)
            px[x, y] = lerp(c1, c2, t) + (255,)
    img = img.filter(ImageFilter.GaussianBlur(SS * 0.012))
    d = ImageDraw.Draw(img, "RGBA")

    # 底部能量光晕(中心偏下一点, 青蓝)
    halo = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo, "RGBA")
    hd.ellipse([SS*0.18, SS*0.22, SS*0.82, SS*0.86], fill=(26, 108, 255, 70))
    halo = halo.filter(ImageFilter.GaussianBlur(SS * 0.10))
    img.alpha_composite(halo)

    # 放射能量射线(细长三角, 青白, 低透明度)
    for i, ang in enumerate((0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)):
        a = math.radians(ang)
        raylen = SS * (0.62 if i % 3 else 0.74)
        x0 = SS*0.5 + math.cos(a) * SS*0.13
        y0 = SS*0.5 + math.sin(a) * SS*0.13
        x1 = SS*0.5 + math.cos(a) * raylen
        y1 = SS*0.5 + math.sin(a) * raylen
        per = math.pi/2 + a
        wpx = SS * 0.008
        d.polygon([(x0 - math.cos(per)*wpx*3, y0 - math.sin(per)*wpx*3),
                   (x0 + math.cos(per)*wpx*3, y0 + math.sin(per)*wpx*3),
                   (x1, y1)], fill=(120, 220, 255, 26))

    # 霓虹 X: 先画两层辉光, 再画渐变本体
    cx = cy = SS * 0.5
    L, W = SS * 0.64, SS * 0.20
    r = W / 2
    def arm_pts(ang):
        a = math.radians(ang)
        p0 = (cx - math.cos(a)*L/2, cy - math.sin(a)*L/2)
        p1 = (cx + math.cos(a)*L/2, cy + math.sin(a)*L/2)
        return p0, p1

    def draw_bar(draw, ang, width, color):
        p0, p1 = arm_pts(ang)
        draw.line([p0, p1], fill=color, width=int(width))
        rr = width / 2
        draw.ellipse([p0[0]-rr, p0[1]-rr, p0[0]+rr, p0[1]+rr], fill=color)
        draw.ellipse([p1[0]-rr, p1[1]-rr, p1[0]+rr, p1[1]+rr], fill=color)

    # 外辉光(青)
    glow1 = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    g1 = ImageDraw.Draw(glow1, "RGBA")
    for ang in (45, -45):
        draw_bar(g1, ang, W*1.9, (0, 226, 255, 150))
    glow1 = glow1.filter(ImageFilter.GaussianBlur(SS * 0.025))
    img.alpha_composite(glow1)
    # 内辉光(紫)
    glow2 = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    g2 = ImageDraw.Draw(glow2, "RGBA")
    for ang in (45, -45):
        draw_bar(g2, ang, W*1.35, (168, 85, 255, 170))
    glow2 = glow2.filter(ImageFilter.GaussianBlur(SS * 0.012))
    img.alpha_composite(glow2)

    # 本体: 双色霓虹 X —— 一条臂纯青, 一条臂纯紫, 交叉处近白
    for ang, color in ((45, (0, 229, 255)), (-45, (168, 85, 255))):
        p0, p1 = arm_pts(ang)
        mask = Image.new("L", (SS, SS), 0)
        md = ImageDraw.Draw(mask)
        md.line([p0, p1], fill=255, width=int(W))
        md.ellipse([p0[0]-r, p0[1]-r, p0[0]+r, p0[1]+r], fill=255)
        md.ellipse([p1[0]-r, p1[1]-r, p1[0]+r, p1[1]+r], fill=255)
        for y in range(SS):
            row = [img.getpixel((x, y)) for x in range(SS)] if False else None
            for x in range(SS):
                if mask.getpixel((x, y)) > 0:
                    img.putpixel((x, y), color + (255,))
    # 中心交叉亮核(近白)
    core = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    cd = ImageDraw.Draw(core, "RGBA")
    cd.ellipse([cx-W*0.30, cy-W*0.30, cx+W*0.30, cy+W*0.30], fill=(250, 252, 255, 255))
    core = core.filter(ImageFilter.GaussianBlur(SS * 0.003))
    img.alpha_composite(core)

    # 星光点缀(左上大星 + 右下小星, 白光带辉)
    def add_star(sx, sy, R1, R2, w, al):
        sl = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        sdd = ImageDraw.Draw(sl, "RGBA")
        star(sdd, sx, sy, R1, R2, w, (255, 255, 255, al))
        sl = sl.filter(ImageFilter.GaussianBlur(SS * 0.010))
        sd2 = ImageDraw.Draw(sl, "RGBA")
        star(sd2, sx, sy, R1, R2, w, (255, 255, 255, al))
        img.alpha_composite(sl)
    add_star(SS*0.22, SS*0.26, SS*0.075, SS*0.032, SS*0.010, 215)
    add_star(SS*0.80, SS*0.74, SS*0.045, SS*0.018, SS*0.008, 170)

    img = img.filter(ImageFilter.GaussianBlur(SS * 0.002))
    return img.resize((S, S), Image.LANCZOS)

if __name__ == "__main__":
    for name, S in (("mipmap-mdpi-v4", 48), ("mipmap-hdpi-v4", 72), ("mipmap-xhdpi-v4", 96),
                    ("mipmap-xxhdpi-v4", 144), ("mipmap-xxxhdpi-v4", 192)):
        out = os.path.join("res", name, "ic_launcher.png")
        make_icon(S).save(out, "PNG")
        print("wrote", out, S)
