#!/usr/bin/env python3
"""ImgX 图标生成器(液态玻璃拟物): 深空蓝黑底 + 悬浮 3D 玻璃 X。
玻璃=白→浅蓝渐变主体 + 上沿镜面高光 + 下沿反光边 + 外缘亮描边 + 背后光晕 + 轻投影。
输出 5 档密度 res/mipmap-*-v4/ic_launcher.png。"""
from PIL import Image, ImageDraw, ImageFilter
import math, os

BG_A, BG_B   = (17, 23, 55), (7, 9, 26)     # 深空对角渐变
HALO         = (70, 120, 235, 64)            # 背后光晕
GLASS_TOP    = (252, 254, 255)               # 玻璃上端(近白)
GLASS_BOTTOM = (168, 196, 238)               # 玻璃下端(浅蓝)
EDGE         = (235, 245, 255, 150)          # 外缘亮描边
HIGHLIGHT    = (255, 255, 255, 175)          # 上沿镜面高光
REFLECT      = (36, 58, 122, 120)            # 下沿反光边
SHADOW       = (0, 2, 12, 95)                # 投影
X_W, X_L     = 0.195, 0.58                   # 玻璃臂宽/长(画布比例)

def make_icon(S):
    SS = S * 4
    img = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    px = img.load()
    # 深空底(对角, 左下略亮)
    for y in range(SS):
        for x in range(SS):
            t = (x + y) / (2.0 * SS)
            px[x, y] = tuple(int(BG_A[i] + (BG_B[i] - BG_A[i]) * t) for i in range(3)) + (255,)
    img = img.filter(ImageFilter.GaussianBlur(SS * 0.012))
    d = ImageDraw.Draw(img, "RGBA")

    cx = cy = SS * 0.5
    W = X_W * SS
    L = X_L * SS
    r = W / 2

    def bar_pts(ang):
        a = math.radians(ang)
        dx, dy = math.cos(a), math.sin(a)
        return ((cx - dx*L/2, cy - dy*L/2), (cx + dx*L/2, cy + dy*L/2))

    def draw_bar(dd, p0, p1, width, color):
        dd.line([p0, p1], fill=color, width=int(width))
        rr = width / 2
        dd.ellipse([p0[0]-rr, p0[1]-rr, p0[0]+rr, p0[1]+rr], fill=color)
        dd.ellipse([p1[0]-rr, p1[1]-rr, p1[0]+rr, p1[1]+rr], fill=color)

    # 背后光晕(大半径柔和)
    halo = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo, "RGBA")
    hd.ellipse([SS*0.14, SS*0.18, SS*0.86, SS*0.82], fill=HALO)
    halo = halo.filter(ImageFilter.GaussianBlur(SS * 0.12))
    img.alpha_composite(halo)

    # 轻投影
    sh = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh, "RGBA")
    for ang in (45, -45):
        p0, p1 = bar_pts(ang)
        draw_bar(sd, (p0[0], p0[1]+SS*0.012), (p1[0], p1[1]+SS*0.012), W, SHADOW)
    sh = sh.filter(ImageFilter.GaussianBlur(SS * 0.008))
    img.alpha_composite(sh)

    # 外缘亮描边(先画, 被主体盖住 2px 形成玻璃亮边)
    edge = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    ed = ImageDraw.Draw(edge, "RGBA")
    for ang in (45, -45):
        p0, p1 = bar_pts(ang)
        draw_bar(ed, p0, p1, W + SS*0.018, EDGE)
    img.alpha_composite(edge)

    # 玻璃主体: 沿臂从近白渐变到浅蓝; 交叉处两臂自然叠加
    for ang in (45, -45):
        p0, p1 = bar_pts(ang)
        ux, uy = (p1[0]-p0[0])/L, (p1[1]-p0[1])/L
        mask = Image.new("L", (SS, SS), 0)
        md = ImageDraw.Draw(mask)
        md.line([p0, p1], fill=255, width=int(W))
        md.ellipse([p0[0]-r, p0[1]-r, p0[0]+r, p0[1]+r], fill=255)
        md.ellipse([p1[0]-r, p1[1]-r, p1[0]+r, p1[1]+r], fill=255)
        for y in range(SS):
            for x in range(SS):
                if mask.getpixel((x, y)) > 0:
                    t = ((x-p0[0])*ux + (y-p0[1])*uy) / L
                    t = max(0.0, min(1.0, t))
                    col = tuple(int(GLASS_TOP[i] + (GLASS_BOTTOM[i] - GLASS_TOP[i]) * t) for i in range(3))
                    img.putpixel((x, y), col + (255,))
    # 上沿镜面高光 + 下沿反光边(逐臂)
    for ang in (45, -45):
        p0, p1 = bar_pts(ang)
        a = math.radians(ang)
        ux, uy = math.cos(a), math.sin(a)
        nx, ny = uy, -ux          # 法线
        # 高光条: 朝上方偏移
        hl = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        hld = ImageDraw.Draw(hl, "RGBA")
        ox, oy = 0, -W * 0.30
        draw_bar(hld, (p0[0]+ox, p0[1]+oy), (p1[0]+ox, p1[1]+oy), W * 0.34, HIGHLIGHT)
        hl = hl.filter(ImageFilter.GaussianBlur(SS * 0.003))
        img.alpha_composite(hl)
        # 下沿反光边
        rl = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
        rld = ImageDraw.Draw(rl, "RGBA")
        ox2, oy2 = 0, W * 0.26
        draw_bar(rld, (p0[0]+ox2, p0[1]+oy2), (p1[0]+ox2, p1[1]+oy2), W * 0.16, REFLECT)
        rl = rl.filter(ImageFilter.GaussianBlur(SS * 0.002))
        img.alpha_composite(rl)
    # 中心亮核(交叉聚焦)
    core = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    cdd = ImageDraw.Draw(core, "RGBA")
    cdd.ellipse([cx-W*0.28, cy-W*0.28, cx+W*0.28, cy+W*0.28], fill=(255, 255, 255, 200))
    core = core.filter(ImageFilter.GaussianBlur(SS * 0.004))
    img.alpha_composite(core)

    return img.resize((S, S), Image.LANCZOS)

if __name__ == "__main__":
    for name, S in (("mipmap-mdpi-v4", 48), ("mipmap-hdpi-v4", 72), ("mipmap-xhdpi-v4", 96),
                    ("mipmap-xxhdpi-v4", 144), ("mipmap-xxxhdpi-v4", 192)):
        out = os.path.join("res", name, "ic_launcher.png")
        make_icon(S).save(out, "PNG")
        print("wrote", out, S)
