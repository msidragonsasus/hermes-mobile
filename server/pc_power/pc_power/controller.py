import json
import socket
import subprocess
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlsplit, urlunsplit

from .config import ConfigurationError, PowerConfig


class PowerActionError(RuntimeError):
    def __init__(self, code, detail):
        super().__init__(detail)
        self.code = code
        self.detail = detail


def magic_packet(mac_address):
    mac = bytes.fromhex(mac_address.replace("-", "").replace(":", ""))
    if len(mac) != 6:
        raise ValueError("invalid MAC")
    return b"\xff" * 6 + mac * 16


class PowerController:
    def __init__(self, config: PowerConfig):
        self.config = config

    def status(self):
        try:
            result = subprocess.run(
                [
                    "/usr/bin/ping",
                    "-c",
                    "1",
                    "-W",
                    str(max(1, round(self.config.status_timeout_seconds))),
                    self.config.target_ipv4,
                ],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=self.config.status_timeout_seconds + 1,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            return "unknown"
        return "awake" if result.returncode == 0 else "asleep_or_unreachable"

    def wake(self):
        packet = magic_packet(self.config.target_mac)
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                for _ in range(3):
                    client.sendto(packet, (self.config.broadcast_ipv4, self.config.wake_port))
        except OSError as error:
            raise PowerActionError("wake_send_failed", "Magic Packetを送信できませんでした。") from error
        return "wake_requested"

    def receiver_health(self):
        try:
            token = self.config.read_sleep_token()
        except ConfigurationError:
            return {"ready": False}
        parsed = urlsplit(self.config.sleep_url)
        health_url = urlunsplit((parsed.scheme, parsed.netloc, "/health", "", ""))
        request = Request(health_url, method="GET", headers={"Authorization": "Bearer " + token})
        try:
            with urlopen(request, timeout=min(self.config.status_timeout_seconds, 1.5)) as response:
                if response.status != 200:
                    return {"ready": False}
                payload = json.loads(response.read(4096))
        except (HTTPError, URLError, OSError, TimeoutError, json.JSONDecodeError):
            return {"ready": False}
        return payload if isinstance(payload, dict) else {"ready": False}

    def receiver_ready(self):
        return self.receiver_health().get("ready") is True

    def sleep(self, operation_id):
        token = self.config.read_sleep_token()
        body = json.dumps({"operation_id": operation_id}, separators=(",", ":")).encode("utf-8")
        request = Request(
            self.config.sleep_url,
            data=body,
            method="POST",
            headers={
                "Authorization": "Bearer " + token,
                "Content-Type": "application/json",
                "Content-Length": str(len(body)),
            },
        )
        try:
            with urlopen(request, timeout=self.config.sleep_timeout_seconds) as response:
                if response.status != 202:
                    raise PowerActionError("sleep_rejected", "対象PCがスリープ要求を受理しませんでした。")
                payload = json.loads(response.read(4096))
        except HTTPError as error:
            raise PowerActionError("sleep_rejected", "対象PCがスリープ要求を拒否しました。") from error
        except (URLError, OSError, TimeoutError, json.JSONDecodeError) as error:
            raise PowerActionError("sleep_result_unknown", "対象PCへ要求を送れましたが、受理結果を確認できません。") from error
        if payload.get("accepted") is not True or payload.get("operation_id") != operation_id:
            raise PowerActionError("sleep_result_unknown", "対象PCから正しい受理確認を取得できません。")
        return "sleep_accepted"
