#!/bin/bash
# Undo deploy.sh. Run as: sudo bash rollback.sh <backup dir printed by deploy.sh>
set -euo pipefail
BK="$1"
ROOT=$(cat "$BK"/agent_root)
cp -p "$BK"/mcp.py "$ROOT"/hermes_cli/web_routers/mcp.py
rm -f "$ROOT"/hermes_cli/mobile_google_workspace.py
systemctl restart home-hermes-dashboard.service
sleep 8
systemctl is-active home-hermes-dashboard.service
echo "rolled back from $BK"
