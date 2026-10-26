#!/bin/bash
# Install the Google Calendar/Drive connection router into the Hermes Dashboard.
# Run as: sudo bash ~/gws-connect-20261024/deploy.sh
# Backs up mcp.py first; restores it automatically if the import check fails.
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
PY=/var/lib/hermes/venv/bin/python
TS=$(date -u +%Y%m%dT%H%M%SZ)
BK=/var/lib/hermes/.hermes/backups/google-workspace-connect-$TS
AS_HERMES=(sudo -u hermes env HOME=/var/lib/hermes HERMES_HOME=/var/lib/hermes/.hermes LANG=C.UTF-8)

[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
STAGE=$(mktemp -d /tmp/gws-connect.XXXXXX)
trap 'rm -rf "$STAGE"' EXIT
install -o hermes -g hermes -m 0644 "$SRC"/mobile_google_workspace.py "$SRC"/prepare_install.py "$SRC"/check_setup.py "$STAGE"/
chown hermes:hermes "$STAGE"
cd /var/lib/hermes/work

ROOT=$("${AS_HERMES[@]}" $PY -I -c 'import hermes_cli,os;print(os.path.dirname(os.path.dirname(os.path.abspath(hermes_cli.__file__))))')
echo "agent root: $ROOT"
echo "== setup check (before) =="
"${AS_HERMES[@]}" $PY -I "$STAGE"/check_setup.py || echo "(setup check failed; continuing - app will show setup_required)"

echo "== dry run =="
"${AS_HERMES[@]}" $PY -I "$STAGE"/prepare_install.py --agent-root "$ROOT"

install -d -o hermes -g hermes -m 0700 "$BK"
cp -p "$ROOT"/hermes_cli/web_routers/mcp.py "$BK"/mcp.py
echo "$ROOT" > "$BK"/agent_root
echo "backup: $BK"

echo "== apply =="
"${AS_HERMES[@]}" $PY -I "$STAGE"/prepare_install.py --agent-root "$ROOT" --apply

echo "== import check =="
if ! "${AS_HERMES[@]}" $PY -I -c 'from hermes_cli.web_routers.mcp import router; p=[r.path for r in router.routes if "google-workspace" in r.path]; print(p); assert p'; then
  echo "IMPORT FAILED - restoring"
  cp -p "$BK"/mcp.py "$ROOT"/hermes_cli/web_routers/mcp.py
  rm -f "$ROOT"/hermes_cli/mobile_google_workspace.py
  echo "restored; dashboard not restarted"; exit 1
fi

echo "== restart dashboard =="
systemctl restart home-hermes-dashboard.service
sleep 8
systemctl is-active home-hermes-dashboard.service
echo "endpoint (401 = route live, needs login): $(curl -s -o /dev/null -w %{http_code} http://100.103.190.68:9119/api/connections/google-workspace)"
echo "DONE. rollback: sudo bash $SRC/rollback.sh $BK"
