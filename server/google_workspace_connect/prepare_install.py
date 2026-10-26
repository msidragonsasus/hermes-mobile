"""Prepare/apply the Google Calendar/Drive connection in the Dashboard. No restart, no config change."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil
import time

MARKER = "from hermes_cli.mobile_google_workspace import create_router as _mobile_google_workspace_router"
INCLUDE = "router.include_router(_mobile_google_workspace_router())"


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
    result = source.replace(anchor, anchor + newline + MARKER + newline + INCLUDE, 1)
    ast.parse(result)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-root", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root = args.agent_root.resolve(strict=True)
    route = root / "hermes_cli/web_routers/mcp.py"
    module = root / "hermes_cli/mobile_google_workspace.py"
    for path in (route, module):
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError("Unexpected installation path")
    original = route.read_bytes()
    encoding = "utf-8-sig" if original.startswith(b"\xef\xbb\xbf") else "utf-8"
    candidate = patch(original.decode(encoding)).encode(encoding)
    content = Path(__file__).with_name("mobile_google_workspace.py").read_bytes()
    compile(content, str(module), "exec")
    if module.exists() and module.read_bytes() != content:
        raise ValueError("A different module exists; review before replacing")
    plan = {"router_sha256_before": hashlib.sha256(original).hexdigest(),
            "router_sha256_after": hashlib.sha256(candidate).hexdigest(),
            "module_sha256": hashlib.sha256(content).hexdigest(), "apply": args.apply}
    if args.apply:
        if route.read_bytes() != original:
            raise ValueError("Router changed while preparing")
        module.write_bytes(content)
        route.write_bytes(candidate)
    print(json.dumps(plan))


if __name__ == "__main__":
    main()
