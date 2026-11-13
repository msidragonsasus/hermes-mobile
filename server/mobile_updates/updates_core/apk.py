"""Read-only identity claims from an APK: binary manifest and APK Signature Scheme v2/v3 signers.

This is a consistency check for the publish step, not signature verification. It reads which
certificate the APK claims to be signed with; Android's package installer and the app's own
PackageManager check verify the signatures cryptographically. Nothing here executes code."""
from dataclasses import dataclass
import hashlib
import io
import struct
import zipfile

V2_ID = 0x7109871A
V3_ID = 0xF05368C0
V31_ID = 0x1B93AD61
PROOF_OF_ROTATION_ID = 0x3BA06F8C
VERSION_CODE_RES = 0x0101021B
VERSION_NAME_RES = 0x0101021C
MANIFEST_LIMIT = 4 * 1024 * 1024
MAGIC = b"APK Sig Block 42"


class ApkError(ValueError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class ApkIdentity:
    application_id: str
    version_code: int
    version_name: str
    signer_sha256: tuple


def _u32(data, offset):
    if offset < 0 or offset + 4 > len(data):
        raise ApkError("apk_malformed")
    return struct.unpack_from("<I", data, offset)[0]


def _u16(data, offset):
    if offset < 0 or offset + 2 > len(data):
        raise ApkError("apk_malformed")
    return struct.unpack_from("<H", data, offset)[0]


def _length_prefixed(data, offset):
    size = _u32(data, offset)
    start, end = offset + 4, offset + 4 + size
    if end > len(data):
        raise ApkError("apk_signature_block_malformed")
    return data[start:end], end


def _sequence(data):
    items, offset = [], 0
    while offset < len(data):
        item, offset = _length_prefixed(data, offset)
        items.append(item)
    return items


def _signing_block(data):
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0 or eocd + 22 > len(data):
        raise ApkError("apk_malformed")
    cd_offset = _u32(data, eocd + 16)
    if cd_offset < 32 or cd_offset > eocd or data[cd_offset - 16:cd_offset] != MAGIC:
        raise ApkError("apk_signature_block_missing")
    footer_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    start = cd_offset - footer_size - 8
    if footer_size < 24 or start < 0 or struct.unpack_from("<Q", data, start)[0] != footer_size:
        raise ApkError("apk_signature_block_malformed")
    pairs, offset, end = {}, start + 8, cd_offset - 24
    while offset < end:
        if offset + 12 > end:
            raise ApkError("apk_signature_block_malformed")
        size = struct.unpack_from("<Q", data, offset)[0]
        if size < 4 or offset + 8 + size > end:
            raise ApkError("apk_signature_block_malformed")
        block_id = _u32(data, offset + 8)
        if block_id in pairs:
            raise ApkError("apk_signature_block_malformed")
        pairs[block_id] = data[offset + 12:offset + 8 + size]
        offset += 8 + size
    return pairs


def _signers(block, v3):
    digests, rotated = [], False
    for signer in _sequence(_length_prefixed(block, 0)[0]):
        signed_data, _ = _length_prefixed(signer, 0)
        _, offset = _length_prefixed(signed_data, 0)
        certificates, offset = _length_prefixed(signed_data, offset)
        certificates = _sequence(certificates)
        if v3:
            offset += 8
        attributes, _ = _length_prefixed(signed_data, offset)
        for attribute in _sequence(attributes):
            if len(attribute) >= 4 and _u32(attribute, 0) == PROOF_OF_ROTATION_ID:
                rotated = True
        if not certificates or not certificates[0]:
            raise ApkError("apk_unsigned")
        digests.append(hashlib.sha256(certificates[0]).hexdigest())
    if not digests:
        raise ApkError("apk_unsigned")
    return tuple(sorted(set(digests))), rotated


def _strings(chunk):
    count, flags, strings_start = _u32(chunk, 8), _u32(chunk, 16), _u32(chunk, 20)
    utf8 = bool(flags & 0x100)
    if count > 100000:
        raise ApkError("manifest_malformed")
    values = []
    for index in range(count):
        offset = strings_start + _u32(chunk, 28 + index * 4)
        if utf8:
            offset += 2 if chunk[offset] & 0x80 else 1
            size = chunk[offset]
            if size & 0x80:
                size = ((size & 0x7F) << 8) | chunk[offset + 1]
                offset += 1
            offset += 1
            raw = chunk[offset:offset + size]
            if len(raw) != size:
                raise ApkError("manifest_malformed")
            values.append(raw.decode("utf-8", "strict"))
        else:
            size = _u16(chunk, offset)
            offset += 2
            if size & 0x8000:
                size = ((size & 0x7FFF) << 16) | _u16(chunk, offset)
                offset += 2
            raw = chunk[offset:offset + size * 2]
            if len(raw) != size * 2:
                raise ApkError("manifest_malformed")
            values.append(raw.decode("utf-16-le", "strict"))
    return values


def _manifest_identity(xml):
    if _u16(xml, 0) != 0x0003 or _u32(xml, 4) != len(xml):
        raise ApkError("manifest_malformed")
    strings, resource_ids, offset = None, [], _u16(xml, 2)
    while offset + 8 <= len(xml):
        kind, header, size = _u16(xml, offset), _u16(xml, offset + 2), _u32(xml, offset + 4)
        if size < 8 or offset + size > len(xml):
            raise ApkError("manifest_malformed")
        chunk = xml[offset:offset + size]
        if kind == 0x0001:
            strings = _strings(chunk)
        elif kind == 0x0180:
            resource_ids = [_u32(chunk, i) for i in range(header, size, 4)]
        elif kind == 0x0102:
            if strings is None:
                raise ApkError("manifest_malformed")
            return _manifest_attributes(chunk, header, strings, resource_ids)
        offset += size
    raise ApkError("manifest_malformed")


def _manifest_attributes(chunk, header, strings, resource_ids):
    def string(index):
        if index >= len(strings):
            raise ApkError("manifest_malformed")
        return strings[index]

    if string(_u32(chunk, header + 4)) != "manifest":
        raise ApkError("manifest_malformed")
    start, size, count = _u16(chunk, header + 8), _u16(chunk, header + 10), _u16(chunk, header + 12)
    values = {}
    for index in range(count):
        offset = header + start + index * size
        name_index = _u32(chunk, offset + 4)
        resource = resource_ids[name_index] if name_index < len(resource_ids) else None
        name = {VERSION_CODE_RES: "versionCode", VERSION_NAME_RES: "versionName"}.get(resource) or string(name_index)
        raw, data_type, data = _u32(chunk, offset + 8), chunk[offset + 15], _u32(chunk, offset + 16)
        if data_type == 0x03:
            values[name] = string(data if raw == 0xFFFFFFFF else raw)
        elif data_type in (0x10, 0x11):
            values[name] = data
    package, code, name = values.get("package"), values.get("versionCode"), values.get("versionName")
    if not isinstance(package, str) or not isinstance(code, int) or not isinstance(name, str):
        raise ApkError("manifest_identity_missing")
    return package, code, name


def inspect_apk(data):
    """Identity of APK bytes (bytes or a read-only mmap, which is used in place without copying)."""
    try:
        with zipfile.ZipFile(data if hasattr(data, "seek") else io.BytesIO(data)) as archive:
            info = archive.getinfo("AndroidManifest.xml")
            if info.file_size > MANIFEST_LIMIT:
                raise ApkError("manifest_malformed")
            manifest = archive.read(info)
    except (zipfile.BadZipFile, KeyError, EOFError, OSError, RuntimeError, NotImplementedError):
        raise ApkError("apk_malformed") from None
    try:
        package, code, name = _manifest_identity(manifest)
    except (IndexError, UnicodeDecodeError, struct.error):
        raise ApkError("manifest_malformed") from None
    try:
        pairs = _signing_block(data)
        if V2_ID not in pairs:
            raise ApkError("apk_signature_block_missing")
        signers, _ = _signers(pairs[V2_ID], v3=False)
        for block_id in (V3_ID, V31_ID):
            if block_id in pairs:
                v3_signers, rotated = _signers(pairs[block_id], v3=True)
                if rotated or v3_signers != signers:
                    raise ApkError("signer_rotation_unsupported")
    except (IndexError, struct.error):
        raise ApkError("apk_signature_block_malformed") from None
    return ApkIdentity(package, code, name, signers)
