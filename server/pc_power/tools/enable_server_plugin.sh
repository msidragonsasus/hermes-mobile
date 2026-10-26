#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root." >&2
    exit 2
fi

plugin_root=/var/lib/hermes/.hermes/plugins/pc-power
config=/var/lib/hermes/.hermes/config.yaml
service=home-hermes-dashboard.service
hermes_cli=/var/lib/hermes/venv/bin/hermes
hermes_python=/var/lib/hermes/venv/bin/python

for path in "$plugin_root/plugin.yaml" "$plugin_root/dashboard/manifest.json" "$plugin_root/dashboard/plugin_api.py" "$config"; do
    if [ ! -f "$path" ] || [ -L "$path" ]; then
        echo "Missing or unexpected file: $path" >&2
        exit 3
    fi
done
for executable in "$hermes_cli" "$hermes_python"; do
    if [ ! -x "$executable" ]; then
        echo "Missing executable: $executable" >&2
        exit 3
    fi
done
if ! systemctl is-active --quiet "$service"; then
    echo "Dashboard service is not active; no changes made." >&2
    exit 4
fi

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=/var/backups/hermes/pc-power-enable-$timestamp
install -d -m 0700 "$backup"
cp -a "$config" "$backup/config.yaml"

rollback=1
restore_config() {
    if [ "$rollback" -eq 1 ]; then
        cp -a "$backup/config.yaml" "$config"
        chown hermes:hermes "$config"
        systemctl restart "$service" >/dev/null 2>&1 || true
        echo "PC power enable failed; config restored from $backup/config.yaml" >&2
    fi
}
trap restore_config EXIT HUP INT TERM

/usr/sbin/runuser -u hermes -- env HOME=/var/lib/hermes HERMES_HOME=/var/lib/hermes/.hermes \
    "$hermes_python" -I -c '
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

/usr/sbin/runuser -u hermes -- env HOME=/var/lib/hermes HERMES_HOME=/var/lib/hermes/.hermes \
    "$hermes_python" -I -c '
from hermes_cli.config import load_config
config = load_config()
plugins = config.get("plugins") or {}
assert "pc-power" in (plugins.get("enabled") or []), "pc-power is not enabled"
assert "pc-power" not in (plugins.get("disabled") or []), "pc-power remains disabled"
entry = (plugins.get("entries") or {}).get("pc-power") or {}
assert not entry.get("allow_tool_override"), "tool override remains granted"
assert "tools.override" not in (entry.get("granted_capabilities") or []), "tool override capability remains granted"
'

systemctl restart "$service"
systemctl is-active --quiet "$service"
rollback=0
trap - EXIT HUP INT TERM
echo "PC_POWER_PLUGIN_ENABLED_WITHOUT_TOOL_OVERRIDE backup=$backup"
