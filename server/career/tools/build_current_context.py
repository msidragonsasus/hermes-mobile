"""Offline validation/generation with the same compiler as the server; no model calls."""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from career_core.context_bundle import choose_context_bundle, validate_manifest
from career_core.sharing import blob_sha, build_current, render_current
from career_core.store import encoded


def compile_repository(root, commit, hashes):
    state = json.loads((root / ".jobhunt/state.json").read_text(encoding="utf-8"))
    manifest = json.loads((root / ".jobhunt/manifest.json").read_text(encoding="utf-8"))
    validate_manifest(manifest, state)
    path, values = manifest.get("context_entry_path"), {}
    if not path:
        raise ValueError("context_entry_path required")
    while path:
        if not re.fullmatch(r"\.jobhunt/context/[A-Za-z0-9._-]+\.json", path) or path in values or len(values) >= 100:
            raise ValueError("invalid context ancestry")
        values[path] = json.loads((root / path).read_text(encoding="utf-8"))
        path = values[path].get("base_context_path")
    active = {".jobhunt/state.json", ".jobhunt/manifest.json"} | set(values)
    if active != set(hashes) or any(not re.fullmatch(r"[0-9a-f]{40}", sha) for sha in hashes.values()):
        raise ValueError("source hashes must cover exactly the active inputs")
    if any(blob_sha((root / path).read_bytes().decode("utf-8")) != sha for path, sha in hashes.items()):
        raise ValueError("input bytes differ from pinned GitHub blobs")
    bundle = choose_context_bundle(values, state, manifest["context_entry_path"])
    return build_current(state, bundle, hashes, commit)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--hashes", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        parser.error("invalid commit")
    value = compile_repository(args.root, args.commit, json.loads(args.hashes.read_text(encoding="utf-8")))
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "current.json").write_text(encoded(value) + "\n", encoding="utf-8", newline="\n")
    (args.output / "current.md").write_text(render_current(value), encoding="utf-8", newline="\n")
    print(encoded({"revision": value["source_revision"], "guards": len(value["context"]["generation_guards"]),
                   "corrections": len(value["context"]["corrections"]), "content_hash": value["content_hash"]}))


if __name__ == "__main__":
    main()
