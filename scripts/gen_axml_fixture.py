#!/usr/bin/env python3
"""生成用于 apk_meta 单元测试的最小合法 binary-XML 样本。

用于 apk_meta 单元测试的最小样本。
运行: python3 scripts/gen_axml_fixture.py
"""
import struct
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "crates/core/tests/fixtures/manifest_sample.bin"

STRINGS = ["manifest", "package", "versionName", "versionCode", "io.github.lianxia233.sample", "2.3.4"]


def u16(v):
    return struct.pack("<H", v)


def u32(v):
    return struct.pack("<I", v)


def string_pool():
    offsets, data = [], bytearray()
    for s in STRINGS:
        b = s.encode("utf-8")
        assert len(b) < 128
        offsets.append(len(data))
        data += bytes([len(s), len(b)]) + b + b"\x00"
    while len(data) % 4:
        data += b"\x00"
    header = u16(0x0001) + u16(28) + u32(28 + 4 * len(STRINGS) + len(data))
    header += u32(len(STRINGS)) + u32(0) + u32(0x100)  # UTF-8 flag
    header += u32(28 + 4 * len(STRINGS)) + u32(0)
    return header + b"".join(u32(o) for o in offsets) + bytes(data)


def start_element():
    attrs = [
        (1, 0xFFFFFFFF, 0x03, 4),          # package = strings[4]
        (2, 0xFFFFFFFF, 0x03, 5),          # versionName = strings[5]
        (3, 0xFFFFFFFF, 0x10, 234),        # versionCode = 234 (int)
    ]
    body = bytearray()
    for name_idx, raw, dtype, data in attrs:
        body += u32(0xFFFFFFFF) + u32(name_idx) + u32(raw)
        body += u16(8) + bytes([0, dtype]) + u32(data)
    head = u16(0x0102) + u16(16) + u32(36 + len(body))
    head += u32(1) + u32(0xFFFFFFFF)                      # line, comment
    head += u32(0xFFFFFFFF) + u32(0)                      # ns, name = "manifest"
    head += u16(20) + u16(20) + u16(len(attrs)) + u16(0) + u16(0) + u16(0)
    return head + bytes(body)


def main():
    sp, el = string_pool(), start_element()
    xml = u16(0x0003) + u16(8) + u32(8 + len(sp) + len(el)) + sp + el
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(xml)
    print(f"wrote {OUT} ({len(xml)} bytes)")


if __name__ == "__main__":
    main()
