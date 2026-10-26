"""Private, process-bound evidence of a mounted Career router's startup."""
import ast
import asyncio
from contextlib import asynccontextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import socket
import stat

STATE = Path("/run/hermes-career-update")
PREFIX = "/api/plugins/career"
REQUIRED = {"/home", "/snapshot", "/coach/conversation", "/collection/notices"}


def release_identity(hashes):
    return hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def process_start(pid="self"):
    return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]


def read_request():
    if not STATE.exists():
        return None
    descriptor = os.open(STATE.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for component in STATE.parts[1:]:
            info = os.fstat(descriptor)
            if info.st_uid != 0 or info.st_mode & 0o022:
                raise RuntimeError("Untrusted deployment directory")
            try:
                child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            except FileNotFoundError:
                return None
            os.close(descriptor)
            descriptor = child
        info = os.fstat(descriptor)
        if info.st_uid != 0 or info.st_mode & 0o027:
            raise RuntimeError("Untrusted deployment directory")
        source = os.open("request.json", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        try:
            info = os.fstat(source)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1 or info.st_mode & 0o027 or info.st_size > 1024 * 1024:
                raise RuntimeError("Untrusted deployment request")
            payload = os.read(source, 1024 * 1024 + 1)
        finally:
            os.close(source)
        request = json.loads(payload)
        if set(request) != {"protocol", "nonce", "release", "sha256", "data_directory"} or request["protocol"] != 1:
            raise RuntimeError("Unsupported deployment request")
        if not isinstance(request["nonce"], str) or len(request["nonce"]) != 64 or any(character not in "0123456789abcdef" for character in request["nonce"]):
            raise RuntimeError("Invalid deployment challenge")
        if not isinstance(request["sha256"], dict) or request["release"] != release_identity(request["sha256"]):
            raise RuntimeError("Invalid deployment release")
        if not isinstance(request["data_directory"], str) or not Path(request["data_directory"]).is_absolute():
            raise RuntimeError("Invalid deployment data directory")
        return request
    finally:
        os.close(descriptor)


def send_receipt(receipt):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(2)
        connection.connect(str(STATE / "receipt.sock"))
        connection.sendall(json.dumps(receipt, sort_keys=True).encode())


def wrap_lifespan(router, receipt, emit):
    expected = [(route.path, route.endpoint, getattr(route, "methods", None)) for route in router.routes]
    if not REQUIRED <= {path for path, endpoint, methods in expected}:
        raise RuntimeError("Deployment router contract missing")
    original = router.lifespan_context

    @asynccontextmanager
    async def lifespan(app):
        async with original(app) as state:
            for path, endpoint, methods in expected:
                if not any(getattr(route, "path", None) == PREFIX + path and
                           getattr(route, "endpoint", None) is endpoint and
                           getattr(route, "methods", None) == methods for route in app.routes):
                    raise RuntimeError("Candidate router was not mounted")
            emit(dict(receipt, pid=os.getpid(), starttime=process_start()))
            yield state

    router.lifespan_context = lifespan


def prepare(root):
    request = read_request()
    if request is None:
        return None
    directory = Path(os.environ.get("CAREER_DATA_DIR", "/var/lib/hermes/career")).resolve(strict=True)
    if not directory.is_dir() or str(directory) != request["data_directory"]:
        raise RuntimeError("Career data directory differs from the running service")
    os.environ["CAREER_DATA_DIR"] = request["data_directory"]
    root = Path(root)
    for name, expected in request["sha256"].items():
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name:
            raise RuntimeError("Invalid deployment member")
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError("Loaded deployment release differs")
    return request


UNPREPARED = object()


def attach(router, root, request=UNPREPARED):
    if request is UNPREPARED:
        request = prepare(root)
    if request is not None:
        wrap_lifespan(router, {key: request[key] for key in ("protocol", "nonce", "release", "data_directory")}, send_receipt)


def check_host_contract(server_source, dashboard_source):
    server = ast.parse(server_source)
    mounts = [node for node in ast.walk(server) if isinstance(node, ast.Call) and
              isinstance(node.func, ast.Name) and node.func.id == "_mount_plugin_api_routes"]
    top_mounts = [node.value for node in server.body if isinstance(node, ast.Expr) and node.value in mounts]
    applications = [node for node in server.body if isinstance(node, ast.Assign) and
                    any(isinstance(target, ast.Name) and target.id == "app" for target in node.targets) and
                    isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name) and
                    node.value.func.id == "FastAPI" and any(keyword.arg == "lifespan" and
                    isinstance(keyword.value, ast.Name) and keyword.value.id == "_lifespan" for keyword in node.value.keywords)]
    if len(mounts) != 1 or top_mounts != mounts or len(applications) != 1 or applications[0].lineno >= mounts[0].lineno:
        raise RuntimeError("Unsupported Dashboard mount lifecycle")
    dashboard = ast.parse(dashboard_source)
    loaders = [node for node in dashboard.body if isinstance(node, ast.FunctionDef) and node.name == "_mount_plugin_api_routes"]
    if len(loaders) != 1:
        raise RuntimeError("Unsupported Dashboard loader")
    loader = loaders[0]
    imports_app = any(isinstance(node, ast.ImportFrom) and node.module == "hermes_cli.web_server" and
                      any(alias.name == "app" and alias.asname is None for alias in node.names) for node in ast.walk(loader))
    includes = [node for node in ast.walk(loader) if isinstance(node, ast.Call) and
                isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name) and
                node.func.value.id == "app" and node.func.attr == "include_router"]
    expected = ast.parse('app.include_router(router, prefix=f"/api/plugins/{plugin[\'name\']}")', mode="eval").body
    if not imports_app or len(includes) != 1 or ast.dump(includes[0], include_attributes=False) != ast.dump(expected, include_attributes=False):
        raise RuntimeError("Unsupported Dashboard router inclusion")


def check_lifespan_contract():
    from fastapi import APIRouter, FastAPI
    events = []

    @asynccontextmanager
    async def host(app):
        events.append("host")
        yield
        events.append("host_closed")

    router = APIRouter()
    for path in REQUIRED:
        router.add_api_route(path, lambda: None)
    router.add_event_handler("startup", lambda: events.append("started"))
    router.add_event_handler("shutdown", lambda: events.append("stopped"))
    original_start = globals()["process_start"]
    globals()["process_start"] = lambda: "synthetic"
    try:
        wrap_lifespan(router, {}, lambda receipt: events.append("receipt"))
        app = FastAPI(lifespan=host)
        app.include_router(router, prefix=PREFIX)

        async def run():
            async with app.router.lifespan_context(app):
                if events != ["host", "started", "receipt"]:
                    raise RuntimeError("Unsupported Dashboard startup ordering")
        asyncio.run(run())
        if events != ["host", "started", "receipt", "stopped", "host_closed"]:
            raise RuntimeError("Unsupported Dashboard shutdown ordering")
    finally:
        globals()["process_start"] = original_start
