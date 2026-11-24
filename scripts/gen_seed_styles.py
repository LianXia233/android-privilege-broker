#!/usr/bin/env python3
"""按 FlClash/prism_ui 的规则，为每个种子色 × 配色风格生成主题样式。

输出 values/pb_seeds.xml（亮色）与 values-night/pb_seeds.xml（暗色），
样式名为 PbSeed_<seed>_<style>，parent 指向 PbPalette，仅覆盖主色组，
表面色仍由 PbPalette 提供（prism 的中性表面与种子色几乎无关）。

用法：python3 scripts/gen_seed_styles.py <android_res_dir>
"""
import os
import sys

from materialyoucolor.hct import Hct
from materialyoucolor.palettes.tonal_palette import TonalPalette

SEEDS = {
    "blue": 0xFF007AFF,
    "violet": 0xFF8B5CF6,
    "magenta": 0xFFD946EF,
    "red": 0xFFEF4444,
    "orange": 0xFFF97316,
    "yellow": 0xFFEAB308,
    "green": 0xFF22C55E,
    "gray": 0xFF6B7280,
}
STYLES = ("vivid", "soft", "mono")
HEADER = ("由 scripts/gen_seed_styles.py 生成，请勿手改。"
          "规则同 FlClash seededColorScheme：vivid=content、soft=tonalSpot、mono=monochrome。")


def hexof(argb: int) -> str:
    return "#%06X" % (argb & 0xFFFFFF)


def tone(pal: TonalPalette, t: float) -> str:
    return hexof(pal.get_hct(t).to_int())


def build(seed: int, style: str, dark: bool) -> dict:
    src = Hct.from_int(seed)
    hue, chroma, seed_tone = src.hue, src.chroma, src.tone

    if style == "vivid":
        # _accent()：饱和强调色（夹到 35..60，暗色上抬 3 夹到 50..75）
        t = min(max(seed_tone + (3.0 if dark else 0.0),
                    50.0 if dark else 35.0),
                75.0 if dark else 60.0)
        primary = seed if abs(t - seed_tone) < 1e-9 else \
            TonalPalette.from_hue_and_chroma(hue, chroma).get_hct(t).to_int()
        on_primary = 0xFFFFFF if t <= 60 else hexof(
            TonalPalette.from_hue_and_chroma(hue, chroma).get_hct(10).to_int())
        container = TonalPalette.from_hue_and_chroma(hue, min(chroma, 48.0))
        container_tone = 30.0 if dark else 90.0
        on_container_tone = 90.0 if dark else 30.0
        secondary = TonalPalette.from_hue_and_chroma(
            hue, max(chroma - 32.0, chroma * 0.5))
    elif style == "soft":
        # tonalSpot：主色 36、次色 16，主色走 M3 的 tone 40/80
        primary_pal = TonalPalette.from_hue_and_chroma(hue, 36.0)
        primary = primary_pal.get_hct(80.0 if dark else 40.0).to_int()
        on_primary = primary_pal.get_hct(20.0 if dark else 100.0).to_int()
        container = primary_pal
        container_tone = 30.0 if dark else 90.0
        on_container_tone = 90.0 if dark else 30.0
        secondary = TonalPalette.from_hue_and_chroma(hue, 16.0)
    else:
        # monochrome：全部 0 chroma
        primary_pal = TonalPalette.from_hue_and_chroma(hue, 0.0)
        primary = primary_pal.get_hct(80.0 if dark else 40.0).to_int()
        on_primary = primary_pal.get_hct(20.0 if dark else 100.0).to_int()
        container = primary_pal
        container_tone = 30.0 if dark else 90.0
        on_container_tone = 90.0 if dark else 30.0
        secondary = primary_pal

    if isinstance(primary, int) and style != "vivid":
        primary = hexof(primary)
    if isinstance(on_primary, int):
        on_primary = hexof(on_primary)

    return {
        "pbPrimary": hexof(primary) if isinstance(primary, int) else primary,
        "pbOnPrimary": on_primary,
        "pbPrimaryContainer": tone(container, container_tone),
        "pbOnPrimaryContainer": tone(container, on_container_tone),
        "pbSecondaryContainer": tone(secondary, 30.0 if dark else 90.0),
        "pbOnSecondaryContainer": tone(secondary, 90.0 if dark else 10.0),
        "pbRipple": (hexof(primary) if isinstance(primary, int) else primary) + "33",
    }


def emit(path: str, dark: bool) -> None:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<!--", f"  {HEADER}", "-->",
             "<resources>"]
    for name, seed in SEEDS.items():
        for style in STYLES:
            colors = build(seed, style, dark)
            lines.append(f'    <style name="PbSeed_{name}_{style}" parent="PbPalette">')
            for key, value in colors.items():
                lines.append(f'        <item name="{key}">{value}</item>')
            lines.append("    </style>")
    lines += ["</resources>", ""]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def main() -> None:
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    res = sys.argv[1]
    emit(os.path.join(res, "values", "pb_seeds.xml"), False)
    emit(os.path.join(res, "values-night", "pb_seeds.xml"), True)
    print("wrote values/pb_seeds.xml 与 values-night/pb_seeds.xml")


if __name__ == "__main__":
    main()
