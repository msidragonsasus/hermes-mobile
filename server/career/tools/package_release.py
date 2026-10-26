"""Create a deterministic code-only archive; never include user data or overwrite output."""
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath


def package(destination, repository=None):
    repository = Path(repository or Path(__file__).resolve().parents[3]).resolve()
    career = repository / "server/career"
    names = json.loads((career / "tools/release_files.json").read_text(encoding="utf-8"))["files"]
    if not isinstance(names, list) or not names or len(names) != len(set(names)):
        raise ValueError("Invalid release allowlist")
    files = {}
    for name in names:
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name:
            raise ValueError("Invalid release path")
        path = repository / name
        if path.resolve() != path or not path.is_file():
            raise ValueError("Missing or redirected release file: " + name)
        if path.stat().st_size > 2 * 1024 * 1024:
            raise ValueError("Unexpected release file size")
        files[name] = path.read_bytes()
    version = re.search(r"(?m)^version: (\S+)$", (career / "plugin.yaml").read_text(encoding="utf-8"))[1]
    if json.loads((career / "dashboard/manifest.json").read_text(encoding="utf-8"))["version"] != version:
        raise RuntimeError("Plugin versions do not match")
    manifest = {"version": version, "contents": "Code, licenses and synthetic tests only; live deployment is unverified",
                "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    files["RELEASE.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 9, 9, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    payload = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Archive verification failed")
    with Path(destination).open("xb") as handle:
        handle.write(payload)
    print(hashlib.sha256(payload).hexdigest())
    return payload


if __name__ == "__main__":
    package(sys.argv[1])
