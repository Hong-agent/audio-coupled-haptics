#!/usr/bin/env python3
"""校验 Ogg/Vorbis 分片里是否存在「独立完整的一条」ANDROID_HAPTIC=1。

平台匹配的是完整独立的一条 comment；用键值拼接 API 写出的粘连字符串
（例如 ENCODER=ENCODERANDROID_HAPTIC=1）会让 HAL 匹配失败 → 能播但完全不震。
本脚本就是规格书 §11.1 第 3 步的可执行版本。

用法:  python3 tools/verify_ogg_comment.py <file.ogg> [<file2.ogg> ...]
退出码: 0 = 全部通过, 1 = 有文件不满足
"""

import sys
import struct

TAG = b"ANDROID_HAPTIC=1"


def packets(data: bytes):
    """按 Ogg 页与 lacing 值切分包。"""
    pos = 0
    current = bytearray()
    while pos + 27 <= len(data):
        if data[pos:pos + 4] != b"OggS":
            pos += 1
            continue
        seg_count = data[pos + 26]
        table = data[pos + 27:pos + 27 + seg_count]
        body_off = pos + 27 + seg_count
        body_len = sum(table)
        if body_off + body_len > len(data):
            break
        off = body_off
        for seg_len in table:
            current += data[off:off + seg_len]
            off += seg_len
            if seg_len < 255:
                yield bytes(current)
                current = bytearray()
        pos = body_off + body_len


def parse_comments(packet: bytes):
    if len(packet) < 7 or packet[0] != 3 or packet[1:7] != b"vorbis":
        return None
    off = 7
    vendor_len = struct.unpack_from("<I", packet, off)[0]
    off += 4 + vendor_len
    count = struct.unpack_from("<I", packet, off)[0]
    off += 4
    comments = []
    for _ in range(count):
        clen = struct.unpack_from("<I", packet, off)[0]
        off += 4
        comments.append(packet[off:off + clen])
        off += clen
    return comments


def check(path: str) -> bool:
    with open(path, "rb") as fh:
        data = fh.read()
    for index, pkt in enumerate(packets(data)):
        comments = parse_comments(pkt)
        if comments is None:
            continue
        exact = [c for c in comments if c == TAG]
        glued = [c for c in comments if c != TAG and TAG in c]
        if exact:
            print(f"[OK]   {path}: 找到独立完整 comment {TAG.decode()}")
            return True
        if glued:
            print(f"[FAIL] {path}: 只找到粘连条目 {glued[0]!r} → 平台匹配会失败")
            return False
        print(f"[FAIL] {path}: comment 列表里没有 {TAG.decode()}")
        print(f"       实际条目: {[c[:60] for c in comments]}")
        return False
    print(f"[FAIL] {path}: 没找到 vorbis comment 头")
    return False


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    ok = True
    for path in argv[1:]:
        ok = check(path) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
