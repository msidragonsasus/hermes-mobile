"""Read-only check run as the Dashboard user. Prints paths and yes/no only, never secrets."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mobile_google_workspace as m  # noqa: E402

setup = m.CoreBackend().setup(None)
out = dict(setup)
if "reason" not in setup:
    creds = setup["credentials_dir"]
    out["credentials_dir_exists"] = os.path.isdir(creds)
    out["credentials_dir_writable"] = os.access(creds if os.path.isdir(creds) else os.path.dirname(creds), os.W_OK)
    out["accounts"] = [{"state": r["state"]} for r in m.CredentialDir(creds).accounts()]
    try:
        m.read_client(setup["client_secret_path"])
        out["client_ok"] = True
    except Exception as error:  # noqa: BLE001
        out["client_ok"] = getattr(error, "detail", "unreadable")
print(json.dumps(out, ensure_ascii=False, indent=1))
