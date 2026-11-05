"""从统一 SVG 母版导出网页、小程序、Android 和 iOS 图标。"""

from __future__ import annotations

import json
import shutil
import sys
import ctypes.util
from pathlib import Path

# macOS 上 Homebrew 的 Cairo 动态库不在系统默认搜索路径中。
if sys.platform == "darwin":
    cairo_library = next((path for path in (
        Path("/opt/homebrew/lib/libcairo.2.dylib"),
        Path("/usr/local/lib/libcairo.2.dylib"),
    ) if path.exists()), None)
    if cairo_library is not None:
        original_find_library = ctypes.util.find_library

        def find_library(name: str) -> str | None:
            if name in ("cairo", "cairo-2", "libcairo-2"):
                return str(cairo_library)
            return original_find_library(name)

        ctypes.util.find_library = find_library

import cairosvg
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
STATIC = ROOT.parent / "backend/src/main/resources/static/brand"
SOURCE = (ROOT / "logo-mark.svg").read_text(encoding="utf-8")


def png(svg: str, path: Path, size: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=str(path), output_width=size, output_height=size)


def copy(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, dst)


def build_web() -> None:
    web = ROOT / "网页"
    web.mkdir(exist_ok=True)
    copy(ROOT / "logo-mark.svg", web / "logo-mark.svg")
    copy(ROOT / "logo-horizontal.svg", web / "logo-horizontal.svg")
    copy(ROOT / "logo-mark.svg", web / "favicon.svg")
    png(SOURCE, web / "favicon-32.png", 32)
    png(SOURCE, web / "apple-touch-icon.png", 180)
    png(SOURCE, web / "pwa-icon-192.png", 192)
    png(SOURCE, web / "pwa-icon-512.png", 512)
    for name in ("logo-mark.svg", "logo-horizontal.svg", "favicon.svg", "favicon-32.png", "apple-touch-icon.png"):
        copy(web / name, STATIC / name)


def build_wechat() -> None:
    folder = ROOT / "微信小程序"
    png(SOURCE, folder / "小程序头像-1024.png", 1024)
    png(SOURCE, folder / "小程序头像-144.png", 144)


def build_android() -> None:
    folder = ROOT / "Android"
    png(SOURCE, folder / "play-store-512.png", 512)
    foreground = SOURCE.replace('<rect width="1024" height="1024" fill="#BD342F"/>', "")
    monochrome = foreground.replace("#FFF8EE", "#000000").replace("#F4C96C", "#000000")
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        target = folder / "res" / f"mipmap-{density}"
        png(SOURCE, target / "ic_launcher.png", size)
        png(foreground, target / "ic_launcher_foreground.png", size * 108 // 48)
        png(monochrome, target / "ic_launcher_monochrome.png", size * 108 // 48)
    adaptive = folder / "res/mipmap-anydpi-v26/ic_launcher.xml"
    adaptive.parent.mkdir(parents=True, exist_ok=True)
    adaptive.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/jinjing_brand_red"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n'
        '</adaptive-icon>\n', encoding="utf-8"
    )
    colors = folder / "res/values/colors.xml"
    colors.parent.mkdir(parents=True, exist_ok=True)
    colors.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources><color name="jinjing_brand_red">#BD342F</color></resources>\n', encoding="utf-8"
    )


def build_ios() -> None:
    folder = ROOT / "iOS/AppIcon.appiconset"
    folder.mkdir(parents=True, exist_ok=True)
    specs = (
        ("iphone", "20x20", "2x", 40), ("iphone", "20x20", "3x", 60),
        ("iphone", "29x29", "2x", 58), ("iphone", "29x29", "3x", 87),
        ("iphone", "40x40", "2x", 80), ("iphone", "40x40", "3x", 120),
        ("iphone", "60x60", "2x", 120), ("iphone", "60x60", "3x", 180),
        ("ipad", "20x20", "1x", 20), ("ipad", "20x20", "2x", 40),
        ("ipad", "29x29", "1x", 29), ("ipad", "29x29", "2x", 58),
        ("ipad", "40x40", "1x", 40), ("ipad", "40x40", "2x", 80),
        ("ipad", "76x76", "1x", 76), ("ipad", "76x76", "2x", 152),
        ("ipad", "83.5x83.5", "2x", 167),
        ("ios-marketing", "1024x1024", "1x", 1024),
    )
    images = []
    for idiom, points, scale, size in specs:
        filename = f"icon-{idiom}-{points}-{scale}.png"
        png(SOURCE, folder / filename, size)
        images.append({"filename": filename, "idiom": idiom, "size": points, "scale": scale})
    (folder / "Contents.json").write_text(
        json.dumps({"images": images, "info": {"author": "xcode", "version": 1}}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def build_preview() -> None:
    canvas = Image.new("RGB", (1440, 920), "#FFF9F3")
    draw = ImageDraw.Draw(canvas)
    font_path = "/System/Library/Fonts/Hiragino Sans GB.ttc"
    title = ImageFont.truetype(font_path, 58) if Path(font_path).exists() else ImageFont.load_default()
    subtitle = ImageFont.truetype(font_path, 25) if Path(font_path).exists() else ImageFont.load_default()
    draw.text((76, 63), "进京了么  ·  品牌预览", font=title, fill="#282A30")
    draw.text((79, 147), "同一枚「京」字印记，适配网页 / 微信小程序 / Android / iOS", font=subtitle, fill="#857B75")
    draw.rounded_rectangle((76, 220, 1364, 804), radius=32, fill="#FFFFFF")
    icon = Image.open(ROOT / "微信小程序/小程序头像-1024.png").convert("RGBA")
    for left, top, size, label in ((156, 302, 348, "主图标 · 1024"), (672, 438, 120, "手机桌面"), (1084, 470, 56, "网页标签")):
        render = icon.resize((size, size), Image.Resampling.LANCZOS)
        canvas.paste(render, (left, top), render)
        draw.text((left, top + size + 18), label, font=subtitle, fill="#625B56")
    draw.text((652, 322), "进京了么", font=title, fill="#282A30")
    draw.text((655, 398), "朱红印章  ·  金色定位", font=subtitle, fill="#857B75")
    draw.text((80, 850), "主色  #BD342F       暖白  #FFF8EE       点缀  #F4C96C", font=subtitle, fill="#857B75")
    canvas.save(ROOT / "预览.png", optimize=True)


if __name__ == "__main__":
    build_web()
    build_wechat()
    build_android()
    build_ios()
    build_preview()
