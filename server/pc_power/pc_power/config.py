import ipaddress
import os
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


class ConfigurationError(ValueError):
    """Raised when a configured power target is unsafe or incomplete."""


_MAC = re.compile(r"^(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")


@dataclass(frozen=True)
class PowerConfig:
    target_name: str
    target_ipv4: str
    target_mac: str
    broadcast_ipv4: str
    wake_port: int
    sleep_url: str
    sleep_token_file: Path
    status_timeout_seconds: float = 1.5
    sleep_timeout_seconds: float = 5.0

    @classmethod
    def from_environment(cls, env=None):
        values = os.environ if env is None else env
        required = (
            "PC_POWER_TARGET_IPV4",
            "PC_POWER_TARGET_MAC",
            "PC_POWER_BROADCAST_IPV4",
            "PC_POWER_SLEEP_URL",
            "PC_POWER_SLEEP_TOKEN_FILE",
        )
        missing = [name for name in required if not str(values.get(name, "")).strip()]
        if missing:
            raise ConfigurationError("missing:" + ",".join(missing))

        target_name = str(values.get("PC_POWER_TARGET_NAME", "desktop-ryzen7")).strip()
        target_address = ipaddress.IPv4Address(values["PC_POWER_TARGET_IPV4"])
        broadcast_address = ipaddress.IPv4Address(values["PC_POWER_BROADCAST_IPV4"])
        if not target_address.is_private or not broadcast_address.is_private or target_address == broadcast_address:
            raise ConfigurationError("target_must_be_private")
        target_ipv4 = str(target_address)
        broadcast_ipv4 = str(broadcast_address)
        target_mac = str(values["PC_POWER_TARGET_MAC"]).strip().upper().replace(":", "-")
        if not target_name or not _MAC.fullmatch(target_mac):
            raise ConfigurationError("invalid_target")

        wake_port = int(values.get("PC_POWER_WAKE_PORT", "9"))
        if not 1 <= wake_port <= 65535:
            raise ConfigurationError("invalid_wake_port")

        sleep_url = str(values["PC_POWER_SLEEP_URL"]).strip()
        parsed = urlsplit(sleep_url)
        if parsed.scheme != "http" or not parsed.hostname or parsed.path != "/sleep" or parsed.query or parsed.fragment:
            raise ConfigurationError("invalid_sleep_url")
        sleep_host = ipaddress.ip_address(parsed.hostname)
        if not (sleep_host.is_private or sleep_host in ipaddress.ip_network("100.64.0.0/10")):
            raise ConfigurationError("sleep_url_must_be_private")

        token_file = Path(str(values["PC_POWER_SLEEP_TOKEN_FILE"]).strip())
        if not token_file.is_absolute():
            raise ConfigurationError("token_file_must_be_absolute")

        status_timeout = float(values.get("PC_POWER_STATUS_TIMEOUT_SECONDS", "1.5"))
        sleep_timeout = float(values.get("PC_POWER_SLEEP_TIMEOUT_SECONDS", "5"))
        if not 0.2 <= status_timeout <= 10 or not 0.5 <= sleep_timeout <= 15:
            raise ConfigurationError("invalid_timeout")

        return cls(
            target_name=target_name,
            target_ipv4=target_ipv4,
            target_mac=target_mac,
            broadcast_ipv4=broadcast_ipv4,
            wake_port=wake_port,
            sleep_url=sleep_url,
            sleep_token_file=token_file,
            status_timeout_seconds=status_timeout,
            sleep_timeout_seconds=sleep_timeout,
        )

    def read_sleep_token(self):
        try:
            token = self.sleep_token_file.read_text(encoding="utf-8-sig").strip()
        except OSError as error:
            raise ConfigurationError("sleep_token_unreadable") from error
        if len(token) < 32 or len(token) > 256 or any(character.isspace() for character in token):
            raise ConfigurationError("sleep_token_invalid")
        return token
