#!/usr/bin/env python3
"""独立校验 APK：v2 签名（按规范重算摘要 + 用 openssl 验签）、ZIP 完整性、不压缩条目的对齐。

用法: verify_apk.py app.apk
"""
import hashlib
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

V2_ID = 0x7109871A
RSA_PKCS1_SHA256 = 0x0103


def lp(buf, off):
    n = struct.unpack_from("<I", buf, off)[0]
    return buf[off + 4: off + 4 + n], off + 4 + n


def items(buf):
    out, off = [], 0
    while off < len(buf):
        v, off = lp(buf, off)
        out.append(v)
    return out


def chunked_sha256(sections):
    digests = []
    for sec in sections:
        for i in range(0, len(sec), 1 << 20):
            chunk = sec[i: i + (1 << 20)]
            digests.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(chunk)) + chunk).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(digests)) + b"".join(digests)).digest()


def main(path):
    data = open(path, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    assert eocd > 0, "找不到 EOCD"
    cd_size, cd_off = struct.unpack_from("<II", data, eocd + 12)
    assert cd_off + cd_size == eocd, "中央目录后面不是 EOCD"

    assert data[cd_off - 16: cd_off] == b"APK Sig Block 42", "没有 APK Signing Block"
    size_tail = struct.unpack_from("<Q", data, cd_off - 24)[0]
    block_start = cd_off - size_tail - 8
    size_head = struct.unpack_from("<Q", data, block_start)[0]
    assert size_head == size_tail, "签名块首尾长度不一致"

    pairs, pos, v2 = data[block_start + 8: cd_off - 24], 0, None
    while pos < len(pairs):
        n = struct.unpack_from("<Q", pairs, pos)[0]
        pid = struct.unpack_from("<I", pairs, pos + 8)[0]
        if pid == V2_ID:
            v2 = pairs[pos + 12: pos + 8 + n]
        pos += 8 + n
    assert v2 is not None, "没有 v2 签名"

    signers = items(lp(v2, 0)[0])
    assert len(signers) == 1
    signed_data, off = lp(signers[0], 0)
    signatures, off = lp(signers[0], off)
    pubkey, off = lp(signers[0], off)

    digests_seq, o = lp(signed_data, 0)
    certs_seq, o = lp(signed_data, o)
    d = items(digests_seq)[0]
    alg = struct.unpack_from("<I", d, 0)[0]
    digest = lp(d, 4)[0]
    s = items(signatures)[0]
    salg = struct.unpack_from("<I", s, 0)[0]
    sig = lp(s, 4)[0]
    cert = items(certs_seq)[0]
    assert alg == salg == RSA_PKCS1_SHA256, "算法不对: %x %x" % (alg, salg)

    eocd_mod = bytearray(data[eocd:])
    struct.pack_into("<I", eocd_mod, 16, block_start)
    computed = chunked_sha256([data[:block_start], data[cd_off:eocd], bytes(eocd_mod)])
    assert computed == digest, "内容摘要不一致"
    print("v2 内容摘要一致")

    with tempfile.TemporaryDirectory() as tmp:
        def w(name, b):
            p = os.path.join(tmp, name)
            open(p, "wb").write(b)
            return p
        pub_der, sig_bin, sd_bin, cert_der = w("pub.der", pubkey), w("sig.bin", sig), w("sd.bin", signed_data), w("cert.der", cert)
        pub_pem = os.path.join(tmp, "pub.pem")
        subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-in", pub_der, "-out", pub_pem], check=True)
        r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub_pem, "-signature", sig_bin, sd_bin],
                           capture_output=True, text=True)
        assert "Verified OK" in r.stdout, "签名校验失败: " + r.stdout + r.stderr
        print("v2 签名校验通过 (openssl)")
        cert_pub = subprocess.run(["openssl", "x509", "-inform", "DER", "-in", cert_der, "-pubkey", "-noout"],
                                  capture_output=True, text=True, check=True).stdout
        assert cert_pub.strip() == open(pub_pem).read().strip(), "证书公钥与签名公钥不一致"
        subj = subprocess.run(["openssl", "x509", "-inform", "DER", "-in", cert_der, "-noout", "-subject", "-enddate"],
                              capture_output=True, text=True, check=True).stdout.strip()
        print("证书:", subj.replace("\n", " | "))

    with zipfile.ZipFile(path) as z:
        bad = z.testzip()
        assert bad is None, "CRC 错误: " + str(bad)
        with open(path, "rb") as f:
            for info in z.infolist():
                f.seek(info.header_offset)
                hdr = f.read(30)
                n, e = struct.unpack("<HH", hdr[26:30])
                start = info.header_offset + 30 + n + e
                if info.compress_type == zipfile.ZIP_STORED:
                    assert start % 4 == 0, "未对齐: " + info.filename
        names = z.namelist()
        assert "classes.dex" in names and "resources.arsc" in names and "AndroidManifest.xml" in names
        assert z.getinfo("resources.arsc").compress_type == zipfile.ZIP_STORED
        dex = z.read("classes.dex")
        assert dex[:8] in (b"dex\n035\0", b"dex\n037\0", b"dex\n038\0"), dex[:8]
        print("ZIP 完整、%d 个条目、不压缩条目均 4 字节对齐、dex 版本 %s" % (len(names), dex[4:7].decode()))
    print("OK")


if __name__ == "__main__":
    main(sys.argv[1])
