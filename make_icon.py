#!/usr/bin/env python3
"""ImgX 应用图标生成器(简约版): 品牌蓝柔和渐变底 + 纯白粗圆头 X + 克制微高光。
规范见 ~/.dsh/skills/app-icon-design/SKILL.md。输出 5 档密度(res/mipmap-*-v4/ic_launcher.png)。"""
from PIL import Image, ImageDraw, ImageFilter
import math, os

# 参数(调这里即可换风格)
BG_TOP   = (62, 155, 255)   # 左上亮蓝
BG_BOTTOM= (21, 100, 232)   # 右下深一档的蓝(同一色相)
X_COLOR  = (255, 255, 255, 255)
X_W      = 0.185             # X 条宽(画布比例)
X_L      = 0.565             # X 条长(画布比例, 留白充足)
SHADOW   = (20, 40, 90, 58)  # 轻投影颜色/透明度
GLOSS_A  = 56                # 顶部玻璃高光透明度

def make_icon(S):
    SS = S * 4
    img = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    px = img.load()
    # 柔和对角渐变(垂直方向渐变更稳, 带 10% 水平偏移模拟光从左上打来)
    for y in range(SS):
        for x in range(SS):
            t = (y / SS) * 0.9 + (x / SS) * 0.1
            px[x, y] = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)) + (255,)
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
        rr = width/2
        dd.ellipse([p0[0]-rr, p0[1]-rr, p0[0]+rr, p0[1]+rr], fill=color)
        dd.ellipse([p1[0]-rr, p1[1]-rr, p1[0]+rr, p1[1]+rr], fill=color)

    # 轻投影(先画, 贴一点, 小模糊)
    sh = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh, "RGBA")
    for ang in (45, -45):
        p0, p1 = bar_pts(ang)
        draw_bar(sd, (p0[0], p0[1] + SS*0.010), (p1[0], p1[1] + SS*0.010), W, SHADOW)
    sh = sh.filter(ImageFilter.GaussianBlur(SS * 0.006))
    img.alpha_composite(sh)

    # 纯白 X
    for ang in (45, -45):
        p0, p1 = bar_pts(ang)
        draw_bar(d, p0, p1, W, X_COLOR)

    # 克制高光: 每条臂上沿一条半透明白(玻璃反光)
    gloss = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gloss, "RGBA")
    for ang in (45, -45):
        a = math.radians(ang)
        p0, p1 = bar_pts(ang)
        per = (math.cos(a + math.pi/2), math.sin(a + math.pi/2))  # 朝向"上"一侧
        # 取垂直于臂、指向右上方的偏移(两条臂都取同一"天顶"方向的高光)
        up = (0, -1)
        off = W * 0.30 * up[0]
        # 简化: 沿臂方向在中心线偏上 W*0.30 处画细条
        ox, oy = up[0] * W * 0.30, up[1] * W * 0.30
        draw_bar(gd, (p0[0]+ox, p0[1]+oy), (p1[0]+ox, p1[1]+oy), W * 0.38, (255, 255, 255, GLOSS_A))
    gloss = gloss.filter(ImageFilter.GaussianBlur(SS * 0.004))
    img.alpha_composite(gloss)

    return img.resize((S, S), Image.LANCZOS)

if __name__ == "__main__":
    for name, S in (("mipmap-mdpi-v4", 48), ("mipmap-hdpi-v4", 72), ("mipmap-xhdpi-v4", 96),
                    ("mipmap-xxhdpi-v4", 144), ("mipmap-xxxhdpi-v4", 192)):
        out = os.path.join("res", name, "ic_launcher.png")
        make_icon(S).save(out, "PNG")
        print("wrote", out, S)
