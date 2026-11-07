#!/usr/bin/env python3
"""从项目图标素材生成全套 Android 启动图标与 UI 矢量图标。

素材: icons/app_icon_source.png (1024x1024 插画，由项目维护者提供)
输出:
  mipmap-*/ic_launcher.png            启动图标（圆形 alpha 蒙版）
  mipmap-*/ic_launcher_round.png      圆形启动图标
  mipmap-*/ic_launcher_backdrop.png   adaptive 背景层（素材采样色铺满）
  mipmap-*/ic_launcher_foreground.png adaptive 前景层（插画 80% 居中）
  mipmap-anydpi-v26/ic_launcher*.xml  adaptive icon 配置
  drawable/ic_launcher_monochrome.xml 单色版（themed icon）
  drawable/ic_action_*.xml            UI 矢量图标
  values/ic_launcher_background.xml   图标背景色

运行: python3 scripts/gen_icons.py   （需要 Pillow）
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
ICONS = ROOT / "icons"
SRC = ICONS / "app_icon_source.png"

DENSITIES = {
    "mipmap-mdpi-v4": (48, 108),
    "mipmap-hdpi-v4": (72, 162),
    "mipmap-xhdpi-v4": (96, 216),
    "mipmap-xxhdpi-v4": (144, 324),
    "mipmap-xxxhdpi-v4": (192, 432),
}

if not SRC.exists():
    raise SystemExit(f"缺少图标素材: {SRC}")

SOURCE = Image.open(SRC).convert("RGB")
W, H = SOURCE.size

_SUPERSAMPLE = 4


def circle_alpha() -> Image.Image:
    """4 倍超采样圆形蒙版，去掉素材四角实色并做边缘抗锯齿。"""
    s = _SUPERSAMPLE
    m = Image.new("L", (W * s, H * s), 0)
    pad = int(W * s * 0.004)
    ImageDraw.Draw(m).ellipse((pad, pad, W * s - pad, H * s - pad), fill=255)
    return m.resize((W, H), Image.LANCZOS)


BASE = SOURCE.convert("RGBA")
BASE.putalpha(circle_alpha())

# adaptive 背景色取自插画左上区域的主色，避开人物主体。
BG = SOURCE.getpixel((int(W * 0.20), int(H * 0.14)))
ICON_BG = "#%02X%02X%02X" % BG[:3]
BG_RGBA = tuple(BG[:3]) + (255,)


def legacy_icon(size: int) -> Image.Image:
    return BASE.resize((size, size), Image.LANCZOS)


def round_icon(size: int) -> Image.Image:
    return legacy_icon(size)


def backdrop(size: int) -> Image.Image:
    """adaptive 背景层铺满采样色，四角不透明。"""
    return Image.new("RGBA", (size, size), BG_RGBA)


def foreground(size: int) -> Image.Image:
    """adaptive 前景层：插画缩放到 80% 居中，四周透明。"""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.80)
    pic = BASE.resize((inner, inner), Image.LANCZOS)
    canvas.alpha_composite(pic, ((size - inner) // 2, (size - inner) // 2))
    return canvas


# ------------------------------------------------------------------ 单色矢量
MONOCHROME = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- APK 文档轮廓 -->
    <path android:pathData="M34,26 L62,26 L76,40 L76,82 L34,82 Z M62,26 L62,40 L76,40"
        android:strokeColor="#FFFFFF" android:strokeWidth="4"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:fillColor="#00000000"/>
    <!-- 文档内的标识条 -->
    <path android:pathData="M40,52 L70,52" android:strokeColor="#FFFFFF"
        android:strokeWidth="5" android:strokeLineCap="round" android:fillColor="#00000000"/>
    <path android:pathData="M40,64 L58,64" android:strokeColor="#FFFFFF"
        android:strokeWidth="4" android:strokeLineCap="round" android:fillColor="#00000000"/>
    <!-- 静默落位箭头 -->
    <path android:pathData="M88,30 L88,52 M79,44 L88,53 L97,44"
        android:strokeColor="#FFFFFF" android:strokeWidth="4"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:fillColor="#00000000"/>
</vector>
'''

ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_backdrop"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''

# ------------------------------------------------------------------ UI 矢量图标
UI_ICONS = {
    "ic_action_install": 'M12,3 L12,14 M7,10 L12,15 L17,10 M4,18 L4,20 L20,20 L20,18',
    "ic_action_uninstall": 'M5,7 L19,7 M9,7 L9,5 L15,5 L15,7 M7,7 L8,20 L16,20 L17,7 M10,10 L10,17 M14,10 L14,17',
    "ic_action_broker": 'M12,3 L20,7 L20,13 Q20,18 12,21 Q4,18 4,13 L4,7 Z M9,12 L11,14 L15,10',
    "ic_action_settings": 'M12,8 A4,4 0 1,0 12,16 A4,4 0 1,0 12,8 M12,2 L13.4,4.2 L16,4.8 L15.4,7.4 L17,9.5 L15.6,11.8 L16.2,14.4 L13.6,15 L12,17.2 L10.4,15 L7.8,14.4 L8.4,11.8 L7,9.5 L8.6,7.4 L8,4.8 L10.6,4.2 Z',
    "ic_action_file": 'M6,2 L14,2 L18,6 L18,22 L6,22 Z M14,2 L14,6 L18,6 M9,12 L15,12 M9,16 L15,16',
    "ic_action_launch": 'M5,5 L19,5 L19,19 L5,19 Z M9,15 L15,9 M11,9 L15,9 L15,13',
    "ic_action_copy": 'M8,8 L20,8 L20,20 L8,20 Z M16,8 L16,4 L4,4 L4,16 L8,16',
    "ic_action_notification": 'M12,3 L12,14 M7,10 L12,15 L17,10 M4,18 L4,20 L20,20 L20,18',
    "ic_action_logs": 'M5,4 L19,4 L19,20 L5,20 Z M8,8 L16,8 M8,12 L16,12 M8,16 L13,16',
    "ic_action_trash": 'M5,7 L19,7 M9,7 L9,5 L15,5 L15,7 M7,7 L8,20 L16,20 L17,7 M10,10 L10,17 M14,10 L14,17',
    "ic_action_share": 'M12,3 L12,15 M8,7 L12,3 L16,7 M5,13 L5,20 L19,20 L19,13',
    "ic_action_bolt": 'M13,2 L6,12 L11,12 L10,22 L18,10 L13,10 Z',
    "ic_action_shield": 'M12,3 L20,6 L20,12 Q20,17 12,21 Q4,17 4,12 L4,6 Z M9,12 L11,14 L16,9',
    "ic_action_apk": 'M7,3 L14,3 L18,7 L18,21 L7,21 Z M14,3 L14,7 L18,7 M10,12 L15,12 M10,16 L15,16',
    "ic_action_back": 'M15,5 L8,12 L15,19',
    "ic_action_refresh": 'M19,7 A8,8 0 1,0 20,13 M19,3 L19,8 L14,8',
}


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


# ------------------------------------------------------------------ 输出
(RES / "drawable").mkdir(parents=True, exist_ok=True)
write(RES / "drawable/ic_launcher_monochrome.xml", MONOCHROME)
write(RES / "mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE)
write(RES / "mipmap-anydpi-v26/ic_launcher_round.xml", ADAPTIVE)
write(RES / "values/ic_launcher_background.xml",
      '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
      f'    <color name="ic_launcher_background">{ICON_BG}</color>\n'
      '</resources>\n')

# 静态矢量资源由布局直接引用，本脚本不清理 drawable 目录，只负责产出下列矢量与位图。

for folder, (legacy, large) in DENSITIES.items():
    out = RES / folder
    out.mkdir(parents=True, exist_ok=True)
    legacy_icon(legacy).save(out / "ic_launcher.png")
    round_icon(legacy).save(out / "ic_launcher_round.png")
    foreground(large).save(out / "ic_launcher_foreground.png")
    backdrop(large).save(out / "ic_launcher_backdrop.png")

for name, path in UI_ICONS.items():
    stroke = "1.8" if name in ("ic_action_broker", "ic_action_shield") else "2"
    write(RES / f"drawable/{name}.xml",
          f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?android:attr/colorControlNormal">
    <path android:pathData="{path}" android:strokeColor="#FFFFFF"
        android:strokeWidth="{stroke}" android:strokeLineCap="round"
        android:strokeLineJoin="round" android:fillColor="#00000000"/>
</vector>
''')

print("icon source :", SRC.relative_to(ROOT))
print("background  :", ICON_BG)
print("png files   :", len(list(RES.rglob("*.png"))))
print("ui vectors  :", len(UI_ICONS))
