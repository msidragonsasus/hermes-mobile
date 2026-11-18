"""Prepare a local Dashboard integration. Applying never restarts or contacts a live server."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil
import time

MARKER = "from hermes_cli.mobile_account_limits import create_router as _mobile_account_limits_router"
INCLUDE = "router.include_router(_mobile_account_limits_router())"


def patch(source):
    ast.parse(source)
    if MARKER in source:
        if source.count(MARKER) != 1 or source.count(INCLUDE) != 1:
            raise ValueError("Unrecognized existing integration")
        return source
    anchor = "router = APIRouter()"
    if source.count(anchor) != 1:
        raise ValueError("MCP router contract differs")
    newline = "\r\n" if "\r\n" in source else "\n"
    candidate = source.replace(anchor, anchor + newline + MARKER + newline + INCLUDE, 1)
    ast.parse(candidate)
    return candidate


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-root", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root = args.agent_root.resolve(strict=True)
    router = root / "hermes_cli/web_routers/mcp.py"
    module = root / "hermes_cli/mobile_account_limits.py"
    for path in (router, module):
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError("Unexpected installation path")
    original = router.read_bytes()
    encoding = "utf-8-sig" if original.startswith(b"\xef\xbb\xbf") else "utf-8"
    candidate = patch(original.decode(encoding)).encode(encoding)
    content = Path(__file__).with_name("mobile_account_limits.py").read_bytes()
    compile(content, str(module), "exec")
    if module.exists() and module.read_bytes() != content:
        raise ValueError("A different module exists; review before replacing")
    plan = {"router_sha256_before": hashlib.sha256(original).hexdigest(),
            "router_sha256_after": hashlib.sha256(candidate).hexdigest(),
            "module_sha256": hashlib.sha256(content).hexdigest(), "apply": args.apply}
    if args.apply:
        if router.read_bytes() != original:
            raise ValueError("Router changed while preparing")
        if candidate != original:
            backup = router.with_name(f"{router.name}.mobile-settings-{time.time_ns()}.bak")
            shutil.copy2(router, backup)
            plan["backup"] = str(backup)
        module.write_bytes(content)
        router.write_bytes(candidate)
    print(json.dumps(plan))


if __name__ == "__main__":
    main()
