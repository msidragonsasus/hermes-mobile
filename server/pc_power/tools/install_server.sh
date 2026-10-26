#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root." >&2
    exit 2
fi
if [ "$#" -ne 1 ]; then
    echo "Usage: install_server.sh STAGING_DIRECTORY" >&2
    exit 2
fi

staging=$1
plugin_root=/var/lib/hermes/.hermes/plugins/pc-power
state_root=/var/lib/hermes/pc-power
config=/var/lib/hermes/.hermes/config.yaml
dropin_root=/etc/systemd/system/home-hermes-dashboard.service.d
dropin=$dropin_root/pc-power.conf
service=home-hermes-dashboard.service

for file in dashboard/manifest.json dashboard/plugin_api.py pc_power/__init__.py pc_power/api.py pc_power/config.py pc_power/controller.py plugin.yaml runtime.env sleep-token; do
    if [ ! -f "$staging/$file" ]; then
        echo "Missing staged file: $file" >&2
        exit 3
    fi
done

python3 - "$staging/dashboard/manifest.json" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if manifest.get("name") != "pc-power" or manifest.get("version") != "0.1.0" or manifest.get("api") != "plugin_api.py":
    raise SystemExit("Unexpected pc-power manifest")
PY

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=/var/backups/hermes/pc-power-$timestamp
install -d -m 0700 "$backup"
if [ -d "$plugin_root" ]; then
    cp -a "$plugin_root" "$backup/plugin"
fi
if [ -d "$state_root" ]; then
    cp -a "$state_root" "$backup/state"
fi
if [ -f "$dropin" ]; then
    cp -a "$dropin" "$backup/pc-power.conf"
fi
if [ -f "$config" ]; then
    cp -a "$config" "$backup/config.yaml"
fi

install -d -o hermes -g hermes -m 0700 "$plugin_root/dashboard" "$plugin_root/pc_power" "$state_root"
install -o hermes -g hermes -m 0600 "$staging/dashboard/manifest.json" "$plugin_root/dashboard/manifest.json"
install -o hermes -g hermes -m 0600 "$staging/dashboard/plugin_api.py" "$plugin_root/dashboard/plugin_api.py"
install -o hermes -g hermes -m 0600 "$staging/pc_power/__init__.py" "$plugin_root/pc_power/__init__.py"
install -o hermes -g hermes -m 0600 "$staging/pc_power/api.py" "$plugin_root/pc_power/api.py"
install -o hermes -g hermes -m 0600 "$staging/pc_power/config.py" "$plugin_root/pc_power/config.py"
install -o hermes -g hermes -m 0600 "$staging/pc_power/controller.py" "$plugin_root/pc_power/controller.py"
install -o hermes -g hermes -m 0600 "$staging/plugin.yaml" "$plugin_root/plugin.yaml"
install -o hermes -g hermes -m 0600 "$staging/runtime.env" "$state_root/runtime.env"
install -o hermes -g hermes -m 0600 "$staging/sleep-token" "$state_root/sleep-token"

install -d -m 0755 "$dropin_root"
printf '%s\n' '[Service]' 'EnvironmentFile=/var/lib/hermes/pc-power/runtime.env' > "$dropin"
chmod 0644 "$dropin"

/usr/sbin/runuser -u hermes -- env HOME=/var/lib/hermes HERMES_HOME=/var/lib/hermes/.hermes \
    /var/lib/hermes/venv/bin/python -I -c '
from hermes_cli.config import load_config, save_config
config = load_config()
plugins = config.setdefault("plugins", {})
enabled = set(plugins.get("enabled") or [])
disabled = set(plugins.get("disabled") or [])
enabled.add("pc-power")
disabled.discard("pc-power")
plugins["enabled"] = sorted(enabled)
plugins["disabled"] = sorted(disabled)
entries = plugins.get("entries") or {}
entry = entries.get("pc-power") or {}
for key in ("allow_tool_override", "granted_capabilities", "capabilities_consent"):
    entry.pop(key, None)
if entry:
    entries["pc-power"] = entry
else:
    entries.pop("pc-power", None)
if entries:
    plugins["entries"] = entries
else:
    plugins.pop("entries", None)
save_config(config)
'
systemctl daemon-reload
systemctl restart "$service"
systemctl is-active --quiet "$service"
echo "PC_POWER_SERVER_INSTALLED backup=$backup"
