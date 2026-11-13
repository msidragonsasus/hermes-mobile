"""Synthetic APK builders for tests. They are structurally valid (ZIP, binary manifest, APK Signing
Block) but carry fake certificates and no real signatures: the server only reads identity claims;
Android verifies signatures cryptographically on the phone."""
import hashlib
import io
import struct
import zipfile

V2_ID = 0x7109871A
V3_ID = 0xF05368C0
PROOF_OF_ROTATION_ID = 0x3BA06F8C
VERSION_CODE_RES = 0x0101021B
VERSION_NAME_RES = 0x0101021C


def _lp(data):
    return struct.pack("<I", len(data)) + data


def _seq(items):
    return _lp(b"".join(_lp(item) for item in items))


def _string_pool(strings, utf8):
    encoded = []
    for text in strings:
        if utf8:
            raw = text.encode("utf-8")
            assert len(text) < 0x80 and len(raw) < 0x80
            encoded.append(bytes([len(text), len(raw)]) + raw + b"\0")
        else:
            raw = text.encode("utf-16-le")
            encoded.append(struct.pack("<H", len(text)) + raw + b"\0\0")
    offsets, cursor = [], 0
    for item in encoded:
        offsets.append(cursor)
        cursor += len(item)
    data = b"".join(encoded)
    data += b"\0" * (-len(data) % 4)
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(data)
    flags = 0x100 if utf8 else 0
    return (struct.pack("<HHIIIIII", 0x0001, header_size, size, len(strings), 0, flags, strings_start, 0)
            + b"".join(struct.pack("<I", o) for o in offsets) + data)


def binary_manifest(package="com.m57.hermescontrol", version_code=2, version_name="1.0.2", utf8=True,
                    root="manifest", version_code_type=0x10):
    strings = ["versionCode", "versionName", "package", root, "http://schemas.android.com/apk/res/android",
               "android", version_name, package]
    index = {text: i for i, text in enumerate(strings)}
    pool = _string_pool(strings, utf8)
    res_map = struct.pack("<HHI", 0x0180, 8, 16) + struct.pack("<II", VERSION_CODE_RES, VERSION_NAME_RES)
    ns_body = struct.pack("<IIII", 1, 0xFFFFFFFF, index["android"], index[strings[4]])
    ns_start = struct.pack("<HHI", 0x0100, 16, 8 + len(ns_body)) + ns_body
    android = index[strings[4]]

    def attr(ns, name, raw, data_type, data):
        return struct.pack("<IIIHBBI", ns, name, raw, 8, 0, data_type, data)

    attrs = [
        attr(android, index["versionCode"], 0xFFFFFFFF, version_code_type, version_code),
        attr(android, index["versionName"], index[version_name], 0x03, index[version_name]),
        attr(0xFFFFFFFF, index["package"], index[package], 0x03, index[package]),
    ]
    ext = struct.pack("<IIHHHHHH", 0xFFFFFFFF, index[root], 20, 20, len(attrs), 0, 0, 0) + b"".join(attrs)
    element = struct.pack("<HHIII", 0x0102, 16, 16 + len(ext), 1, 0xFFFFFFFF) + ext
    body = pool + res_map + ns_start + element
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def v2_block(certificates_per_signer):
    signers = []
    for certificates in certificates_per_signer:
        signed_data = _seq([b"digest"]) + _seq(certificates) + _seq([])
        signers.append(_lp(signed_data) + _seq([b"signature"]) + _lp(b"public-key"))
    return _seq(signers)


def v3_block(certificates_per_signer, rotation=False):
    signers = []
    for certificates in certificates_per_signer:
        attributes = [struct.pack("<I", PROOF_OF_ROTATION_ID) + b"lineage"] if rotation else []
        signed_data = (_seq([b"digest"]) + _seq(certificates) + struct.pack("<II", 28, 0x7FFFFFFF)
                       + _seq(attributes))
        signers.append(_lp(signed_data) + struct.pack("<II", 28, 0x7FFFFFFF) + _seq([b"sig"]) + _lp(b"pk"))
    return _seq(signers)


def signing_block(pairs):
    body = b"".join(struct.pack("<QI", len(value) + 4, block_id) + value for block_id, value in pairs)
    size = len(body) + 8 + 16
    return struct.pack("<Q", size) + body + struct.pack("<Q", size) + b"APK Sig Block 42"


def build_apk(manifest=None, certificates=(b"fake-certificate-der",), v3=None, rotation=False, extra_pairs=(),
              with_signing_block=True):
    manifest = binary_manifest() if manifest is None else manifest
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("AndroidManifest.xml", manifest)
        archive.writestr("classes.dex", b"dex\n035\0synthetic")
    data = buffer.getvalue()
    if not with_signing_block:
        return data
    pairs = [(V2_ID, v2_block([list(certificates)]))]
    if v3 is not None:
        pairs.append((V3_ID, v3_block([list(v3)], rotation=rotation)))
    pairs.extend(extra_pairs)
    block = signing_block(pairs)
    eocd = data.rfind(b"PK\x05\x06")
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    patched_eocd = data[eocd:eocd + 16] + struct.pack("<I", cd_offset + len(block)) + data[eocd + 20:]
    return data[:cd_offset] + block + data[cd_offset:eocd] + patched_eocd


def digest(data):
    return hashlib.sha256(data).hexdigest()
