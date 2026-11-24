#!/usr/bin/env python3
"""按 FlClash / prism_ui 的 seededColorScheme 规则生成 Android 色板资源。

对应源码：FlClash/plugins/prism_ui/lib/color.dart
  - 种子 0xFF007AFF（FlClash defaultPrimaryColor），ColorStyle.vivid
  - 主色走自定义 _accent()（饱和强调色，而不是 M3 的 tone 40/80）
  - 中性面走 custom surface chroma（亮 1.5 / 暗 3.0）与自定义暗色容器 tone
  - success / warning 使用固定色板（#4CAF50 / #FF9800）
同时输出旧布局仍在用的语义别名（window_bg / text_primary / brand_primary …），
让尚未重写的页面也能立刻换上新配色。

用法：python3 scripts/gen_palette.py <android_res_dir>
"""
import math
import os
import sys

from materialyoucolor.hct import Hct
from materialyoucolor.palettes.tonal_palette import TonalPalette

SEED = 0xFF007AFF

# prism_ui/color.dart 中的常量
SURFACE_CHROMA = 3.0
PAPER_CHROMA = 1.5
PRIMARY_CONTAINER_CHROMA = 48.0
STATUS_CONTAINER_CHROMA = 16.0
DARK_ON_SURFACE_TONE = 97.0
DARK_CARD_TONE = 15.0
DARK_BLOCK_TONE = 21.0
DARK_INSET_TONE = 27.0
MIN_ACCENT_TONE = 35.0
MAX_ACCENT_TONE = 60.0
DARK_ACCENT_LIFT = 3.0
MIN_DARK_ACCENT_TONE = 50.0
MAX_DARK_ACCENT_TONE = 75.0


def hexof(argb: int) -> str:
    return "#%06X" % (argb & 0xFFFFFF)


def hct_int(hue: float, chroma: float, tone: float) -> int:
    # 该库的 Hct 构造器不接收 (h, c, t)，用同参数的色板取目标 tone，等价。
    return TonalPalette.from_hue_and_chroma(hue, chroma).get_hct(tone).to_int()


def hct_hex(hue: float, chroma: float, tone: float) -> str:
    return hexof(hct_int(hue, chroma, tone))


def palette_hex(palette: TonalPalette, tone: float) -> str:
    return hexof(palette.get_hct(tone).to_int())


def build(dark: bool) -> dict:
    src = Hct.from_int(SEED)
    hue, chroma, tone = src.hue, src.chroma, src.tone

    # _accent()：亮色夹到 35..60，暗色上抬 3 并夹到 50..75
    if dark:
        accent_tone = min(max(tone + DARK_ACCENT_LIFT, MIN_DARK_ACCENT_TONE),
                          MAX_DARK_ACCENT_TONE)
    else:
        accent_tone = min(max(tone, MIN_ACCENT_TONE), MAX_ACCENT_TONE)
    if abs(accent_tone - tone) < 1e-9:
        primary = SEED
    else:
        primary = hct_int(hue, chroma, accent_tone)
    on_primary = 0xFFFFFF if accent_tone <= MAX_ACCENT_TONE else hct_int(
        hue, chroma, 10.0)

    seed_palette = TonalPalette.from_hue_and_chroma(hue, chroma)
    container = TonalPalette.from_hue_and_chroma(
        hue, min(chroma, PRIMARY_CONTAINER_CHROMA))
    secondary = TonalPalette.from_hue_and_chroma(
        hue, max(chroma - 32.0, chroma * 0.5))
    tertiary = TonalPalette.from_hue_and_chroma((hue + 60.0) % 360.0, chroma)
    neutral_variant = TonalPalette.from_hue_and_chroma(hue, chroma / 8.0 + 4.0)
    error_palette = TonalPalette.from_hue_and_chroma(25.0, 84.0)
    success_palette = TonalPalette.from_int(0xFF4CAF50)
    warning_palette = TonalPalette.from_int(0xFFFF9800)
    success_container = TonalPalette.from_hue_and_chroma(
        success_palette.get_hct(50.0).hue,
        min(success_palette.get_hct(50.0).chroma, STATUS_CONTAINER_CHROMA))
    warning_container = TonalPalette.from_hue_and_chroma(
        warning_palette.get_hct(50.0).hue,
        min(warning_palette.get_hct(50.0).chroma, STATUS_CONTAINER_CHROMA))

    def pick(pal: TonalPalette, light: float, dark_tone: float) -> str:
        return palette_hex(pal, dark_tone if dark else light)

    def surface(tone_value: float, cap: float, dark_tone=None) -> str:
        t = dark_tone if (dark and dark_tone is not None) else tone_value
        return hct_hex(hue, min(chroma / 8.0, cap), t)

    c = {
        "primary": hexof(primary),
        "on_primary": hexof(on_primary),
        "primary_container": palette_hex(container, 30.0 if dark else 90.0),
        "on_primary_container": palette_hex(container, 90.0 if dark else 30.0),
        "primary_fixed": palette_hex(seed_palette, 90.0),
        "primary_fixed_dim": palette_hex(seed_palette, 80.0),
        "on_primary_fixed": palette_hex(seed_palette, 10.0),
        "on_primary_fixed_variant": palette_hex(seed_palette, 30.0),
        "secondary": pick(secondary, 40.0, 80.0),
        "on_secondary": pick(secondary, 100.0, 20.0),
        "secondary_container": pick(secondary, 90.0, 30.0),
        "on_secondary_container": pick(secondary, 10.0, 90.0),
        "secondary_fixed": palette_hex(secondary, 90.0),
        "secondary_fixed_dim": palette_hex(secondary, 80.0),
        "on_secondary_fixed": palette_hex(secondary, 10.0),
        "on_secondary_fixed_variant": palette_hex(secondary, 30.0),
        "tertiary": pick(tertiary, 40.0, 80.0),
        "on_tertiary": pick(tertiary, 100.0, 20.0),
        "tertiary_container": pick(tertiary, 90.0, 30.0),
        "on_tertiary_container": pick(tertiary, 10.0, 90.0),
        "tertiary_fixed": palette_hex(tertiary, 90.0),
        "tertiary_fixed_dim": palette_hex(tertiary, 80.0),
        "on_tertiary_fixed": palette_hex(tertiary, 10.0),
        "on_tertiary_fixed_variant": palette_hex(tertiary, 30.0),
        "error": pick(error_palette, 40.0, 80.0),
        "on_error": pick(error_palette, 100.0, 20.0),
        "error_container": pick(error_palette, 90.0, 30.0),
        "on_error_container": pick(error_palette, 10.0, 90.0),
        "success": pick(success_palette, 40.0, 80.0),
        "on_success": pick(success_palette, 100.0, 20.0),
        "success_container": pick(success_container, 90.0, 30.0),
        "on_success_container": pick(success_palette, 30.0, 90.0),
        "warning": pick(warning_palette, 40.0, 80.0),
        "on_warning": pick(warning_palette, 100.0, 20.0),
        "warning_container": pick(warning_container, 90.0, 30.0),
        "on_warning_container": pick(warning_palette, 30.0, 90.0),
        "surface": surface(6.0 if dark else 98.0, PAPER_CHROMA),
        "surface_bright": surface(24.0 if dark else 98.0, PAPER_CHROMA),
        "surface_dim": surface(6.0 if dark else 87.0, SURFACE_CHROMA),
        "surface_container_lowest": surface(4.0 if dark else 100.0, SURFACE_CHROMA),
        "surface_container_low": surface(10.0 if dark else 96.0, SURFACE_CHROMA),
        "surface_container": surface(94.0, SURFACE_CHROMA, DARK_CARD_TONE),
        "surface_container_high": surface(92.0, SURFACE_CHROMA, DARK_BLOCK_TONE),
        "surface_container_highest": surface(90.0, SURFACE_CHROMA, DARK_INSET_TONE),
        "on_surface": hct_hex(hue, chroma / 8.0, DARK_ON_SURFACE_TONE if dark else 10.0),
        "on_surface_variant": pick(neutral_variant, 30.0, 80.0),
        "outline": pick(neutral_variant, 50.0, 60.0),
        "outline_variant": hct_hex(hue, min(chroma / 8.0 + 4.0, 4.0),
                                  30.0 if dark else 80.0),
        "inverse_surface": hct_hex(hue, chroma / 8.0, 90.0 if dark else 20.0),
        "on_inverse_surface": hct_hex(hue, chroma / 8.0, 20.0 if dark else 95.0),
        "inverse_primary": pick(seed_palette, 80.0, 40.0),
        "shadow": "#000000",
        "scrim": "#000000",
    }

    # SurfaceLevel（prism_ui/surface.dart）：page / card / block / inset
    c["page"] = c["surface"] if dark else c["surface_container"]
    c["card"] = c["surface_container"] if dark else c["surface_bright"]
    c["block"] = c["surface_container_high"] if dark else c["surface_container"]
    c["inset"] = c["surface_container_highest"]
    c["floating"] = c["block"] if dark else c["card"]
    c["nav_indicator"] = c["inset"] if dark else c["block"]
    c["destructive"] = "#FF4245" if dark else "#FF383C"
    return c


def emit(cols: dict, path: str, header: str) -> None:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        "  由 tools/gen_palette.py 生成，请勿手改。",
        "  规则与 FlClash / prism_ui seededColorScheme 一致：种子 0xFF007AFF、vivid 风格。",
        f"  {header}",
        "-->",
        "<resources>",
    ]
    for name, value in cols.items():
        lines.append(f'    <color name="pb_{name}">{value}</color>')
    lines.append("")
    lines.append("    <!-- 旧语义别名：让尚未重写的页面立即使用新配色 -->")
    aliases = {
        "window_bg": "page",
        "card_bg": "card",
        "card_soft": "block",
        "input_bg": "inset",
        "chip_bg": "block",
        "log_bg": "page",
        "text_primary": "on_surface",
        "text_secondary": "on_surface_variant",
        "text_muted": "outline",
        "divider": "outline_variant",
        "brand_primary": "primary",
        "brand_accent": "tertiary",
        "brand_deep": "primary_container",
        "on_brand": "on_primary",
        "success": "success",
        "danger": "error",
        "warning": "warning",
        "badge_ok_bg": "success_container",
        "badge_warn_bg": "warning_container",
    }
    for alias, target in aliases.items():
        lines.append(f'    <color name="{alias}">@color/pb_{target}</color>')
    ripple = cols["primary"]
    lines.append(f'    <color name="ripple_soft">{ripple}33</color>')
    lines += ["</resources>", ""]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def main() -> None:
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    res = sys.argv[1]
    light = build(False)
    dark = build(True)
    emit(light, os.path.join(res, "values", "colors.xml"), "亮色板")
    emit(dark, os.path.join(res, "values-night", "colors.xml"), "暗色板")
    print("主要色值：")
    for key in ("primary", "on_primary", "primary_container", "page", "card",
                "block", "inset", "on_surface", "on_surface_variant", "success"):
        print(f"  {key:20s} light={light[key]}  dark={dark[key]}")


if __name__ == "__main__":
    main()
