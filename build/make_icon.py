# -*- coding: utf-8 -*-
"""生成应用图标：深色玻璃底 + 蓝绿渐变圆角方块 + 白色「签」字。

输出：
  build/appicon.png          1024x1024 主图（Wails 构建时读取）
  build/windows/icon.ico     多尺寸 ico（16/24/32/48/64/128/256）
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

BASE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(BASE)
SIZE = 1024

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def build(size=SIZE):
    # 背景：近乎纯黑，带一点蓝紫氛围（与界面 --bg 一致）
    img = Image.new("RGB", (size, size), (8, 10, 15))
    # 光晕层
    glow = Image.new("RGB", (size, size), (8, 10, 15))
    gd = ImageDraw.Draw(glow)
    gd.ellipse(
        [-size * 0.35, -size * 0.45, size * 0.95, size * 0.75],
        fill=(38, 60, 120),
    )
    gd.ellipse(
        [size * 0.35, size * 0.45, size * 1.25, size * 1.15],
        fill=(16, 90, 90),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.12))
    img = Image.blend(img, glow, 0.55)

    # 渐变圆角方块
    pad = int(size * 0.135)
    box = (pad, pad, size - pad, size - pad)
    radius = int(size * 0.235)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)

    grad = Image.new("RGB", (size, size), (0, 0, 0))
    gpx = grad.load()
    c1 = (109, 140, 255)   # --accent  #6d8cff
    c2 = (69, 216, 196)    # --accent-2 #45d8c4
    x0, y0, x1, y1 = box
    span = (x1 - x0) + (y1 - y0)
    for y in range(size):
        for x in range(size):
            t = ((x - x0) + (y - y0)) / span
            t = 0.0 if t < 0 else (1.0 if t > 1 else t)
            gpx[x, y] = lerp(c1, c2, t)

    img.paste(grad, (0, 0), mask)

    # 内描边：顶部亮线，模拟玻璃高光
    hl = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(hl).rounded_rectangle(
        box, radius=radius, outline=(255, 255, 255, 90), width=max(2, size // 256)
    )
    img = Image.alpha_composite(img.convert("RGBA"), hl)

    # 「签」字
    draw = ImageDraw.Draw(img)
    font = load_font(int(size * 0.46))
    text = "签"
    bbox = draw.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = (size - tw) / 2 - bbox[0]
    ty = (size - th) / 2 - bbox[1] - size * 0.012

    # 文字阴影
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text(
        (tx, ty + size * 0.02), text, font=font, fill=(6, 20, 40, 130)
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(size * 0.018))
    img = Image.alpha_composite(img, shadow)
    draw = ImageDraw.Draw(img)
    draw.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))

    return img.convert("RGB")


def main():
    icon = build()
    png_path = os.path.join(BASE, "appicon.png")
    icon.save(png_path, "PNG")
    print("wrote", png_path, icon.size)

    ico_path = os.path.join(BASE, "windows", "icon.ico")
    os.makedirs(os.path.dirname(ico_path), exist_ok=True)
    sizes = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (24, 24), (16, 16)]
    icon.save(ico_path, format="ICO", sizes=sizes)
    print("wrote", ico_path)

    # 预览图（160px，用于 README）
    preview = icon.resize((160, 160), Image.LANCZOS)
    preview_path = os.path.join(BASE, "appicon-preview.png")
    preview.save(preview_path, "PNG")
    print("wrote", preview_path)


if __name__ == "__main__":
    main()
