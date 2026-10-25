#!/usr/bin/env python3
"""把 aapt2 生成的资源包和 classes.dex 合成一个未签名、已对齐的 APK。

不压缩的条目（resources.arsc、图片等）按 4 字节对齐，和 zipalign 的效果一样，
对齐用的是 Android 规定的 0xD935 扩展字段。

用法: pack_apk.py res.apk classes.dex out.apk
"""
import struct
import sys
import zipfile

ALIGN = 4
FIXED_TIME = (2008, 1, 1, 0, 0, 0)


def main(res_apk, dex_path, out_path):
    with zipfile.ZipFile(res_apk) as src:
        entries = [(info, src.read(info.filename)) for info in src.infolist()]
    with open(dex_path, "rb") as f:
        dex = f.read()

    names = [i.filename for i, _ in entries]
    if "classes.dex" in names:
        raise SystemExit("res.apk 里已经有 classes.dex")

    # 顺序：AndroidManifest.xml、classes.dex、其余资源
    ordered = []
    for info, data in entries:
        if info.filename == "AndroidManifest.xml":
            ordered.append((info.filename, data, info.compress_type))
    ordered.append(("classes.dex", dex, zipfile.ZIP_DEFLATED))
    for info, data in entries:
        if info.filename != "AndroidManifest.xml":
            ordered.append((info.filename, data, info.compress_type))

    with open(out_path, "wb") as fp:
        with zipfile.ZipFile(fp, "w") as out:
            for name, data, ctype in ordered:
                zi = zipfile.ZipInfo(name, date_time=FIXED_TIME)
                zi.compress_type = ctype
                zi.create_system = 0
                zi.external_attr = 0
                if ctype == zipfile.ZIP_STORED:
                    header_offset = fp.tell()
                    data_start = header_offset + 30 + len(name.encode("utf-8")) + 6
                    pad = (-data_start) % ALIGN
                    zi.extra = struct.pack("<HHH", 0xD935, 2 + pad, ALIGN) + b"\0" * pad
                out.writestr(zi, data, compress_type=ctype, compresslevel=9 if ctype == zipfile.ZIP_DEFLATED else None)

    # 自检：所有不压缩的条目都要对齐
    with zipfile.ZipFile(out_path) as z, open(out_path, "rb") as f:
        for info in z.infolist():
            f.seek(info.header_offset)
            hdr = f.read(30)
            n, e = struct.unpack("<HH", hdr[26:30])
            start = info.header_offset + 30 + n + e
            if info.compress_type == zipfile.ZIP_STORED and start % ALIGN:
                raise SystemExit("对齐失败: %s @ %d" % (info.filename, start))
    print("packed %d entries -> %s" % (len(ordered), out_path))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
