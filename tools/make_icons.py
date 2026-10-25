#!/usr/bin/env python3
"""生成 App 图标（行走的小人）：自适应图标前景 + 通知栏小图标。"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "res")

# 以 108 为单位的坐标（自适应图标画布 108dp，安全区为中间 66dp）
HEAD = (57.0, 33.0, 6.6)
STROKE = 7.0
SEGMENTS = [
    [(55.0, 42.5), (51.0, 62.0)],                 # 躯干
    [(54.5, 46.0), (61.0, 53.5), (67.5, 56.5)],   # 前臂
    [(54.5, 46.0), (47.5, 52.0), (44.0, 59.5)],   # 后臂
    [(51.0, 62.0), (58.5, 71.0), (57.5, 80.5)],   # 前腿
    [(51.0, 62.0), (46.0, 72.0), (38.5, 78.0)],   # 后腿
]

SS = 4  # 超采样倍数，抗锯齿


def draw_figure(size_px, scale, ox, oy, color=(255, 255, 255, 255)):
    big = size_px * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def tp(x, y):
        return ((x * scale + ox) * SS, (y * scale + oy) * SS)

    w = STROKE * scale * SS
    r = w / 2
    for seg in SEGMENTS:
        pts = [tp(x, y) for x, y in seg]
        d.line(pts, fill=color, width=int(round(w)))
        for (px, py) in pts:  # 圆头、圆角
            d.ellipse((px - r, py - r, px + r, py + r), fill=color)
    hx, hy = tp(HEAD[0], HEAD[1])
    hr = HEAD[2] * scale * SS
    d.ellipse((hx - hr, hy - hr, hx + hr, hy + hr), fill=color)
    return img.resize((size_px, size_px), Image.LANCZOS)


def main():
    fg_sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for dens, px in fg_sizes.items():
        out = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(out, exist_ok=True)
        draw_figure(px, px / 108.0, 0, 0).save(os.path.join(out, "ic_launcher_fg.png"), optimize=True)

    # 通知栏小图标 24dp：把小人的外框 (36..71, 26..84) 放进 24 个单位里
    stat_sizes = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}
    for dens, px in stat_sizes.items():
        unit = px / 24.0
        scale = unit * 22.0 / 58.0
        ox = unit * 12.0 - 53.5 * scale
        oy = unit * 12.0 - 55.0 * scale
        out = os.path.join(RES, "drawable-" + dens)
        os.makedirs(out, exist_ok=True)
        draw_figure(px, scale, ox, oy).save(os.path.join(out, "ic_stat.png"), optimize=True)

    # 预览图（不打包进 App）
    if len(sys.argv) > 1:
        prev = Image.new("RGBA", (432, 432), (0x2E, 0x7D, 0x5B, 255))
        prev.alpha_composite(draw_figure(432, 4.0, 0, 0))
        mask = Image.new("L", (432, 432), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, 431, 431), radius=100, fill=255)
        prev.putalpha(mask)
        canvas = Image.new("RGBA", (560, 560), (242, 244, 243, 255))
        canvas.alpha_composite(prev, (64, 64))
        stat = draw_figure(96, 96 / 24.0 * 22.0 / 58.0, 48 - 53.5 * (96 / 24.0 * 22.0 / 58.0), 48 - 55.0 * (96 / 24.0 * 22.0 / 58.0), (40, 40, 40, 255))
        canvas.alpha_composite(stat, (440, 440))
        canvas.save(sys.argv[1])


if __name__ == "__main__":
    main()
