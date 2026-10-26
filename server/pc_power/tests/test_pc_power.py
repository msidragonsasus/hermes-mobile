import json
import socket
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from pc_power.config import ConfigurationError, PowerConfig
from pc_power.controller import PowerActionError, PowerController, magic_packet


class PowerConfigTests(unittest.TestCase):
    def environment(self, token_file):
        return {
            "PC_POWER_TARGET_NAME": "desktop-ryzen7",
            "PC_POWER_TARGET_IPV4": "192.168.0.11",
            "PC_POWER_TARGET_MAC": "9C-6B-00-88-78-CB",
            "PC_POWER_BROADCAST_IPV4": "192.168.0.255",
            "PC_POWER_SLEEP_URL": "http://100.92.109.109:9120/sleep",
            "PC_POWER_SLEEP_TOKEN_FILE": str(token_file),
        }

    def test_rejects_missing_public_or_non_sleep_configuration(self):
        with self.assertRaises(ConfigurationError):
            PowerConfig.from_environment({})
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            for url in ("https://example.com/sleep", "http://8.8.8.8/sleep", "http://100.92.109.109/run"):
                env = self.environment(token)
                env["PC_POWER_SLEEP_URL"] = url
                with self.assertRaises(ConfigurationError):
                    PowerConfig.from_environment(env)
            env = self.environment(token)
            env["PC_POWER_TARGET_IPV4"] = "8.8.8.8"
            with self.assertRaises(ConfigurationError):
                PowerConfig.from_environment(env)

    def test_reads_strict_secret_without_exposing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("a" * 48 + "\n", encoding="utf-8")
            config = PowerConfig.from_environment(self.environment(token))
            self.assertEqual("a" * 48, config.read_sleep_token())
            token.write_text("b" * 48, encoding="utf-8-sig")
            self.assertEqual("b" * 48, config.read_sleep_token())
            token.write_text("too short", encoding="utf-8")
            with self.assertRaises(ConfigurationError):
                config.read_sleep_token()


class PowerControllerTests(unittest.TestCase):
    def config(self, token_file):
        return PowerConfig.from_environment(PowerConfigTests().environment(token_file))

    def test_magic_packet_has_expected_shape(self):
        packet = magic_packet("9C-6B-00-88-78-CB")
        self.assertEqual(102, len(packet))
        self.assertEqual(b"\xff" * 6, packet[:6])
        self.assertEqual(bytes.fromhex("9C6B008878CB") * 16, packet[6:])

    def test_status_uses_fixed_ping_binary_and_reports_reachability(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch("subprocess.run") as run:
            run.return_value.returncode = 0
            controller = PowerController(self.config(Path(directory) / "token"))
            self.assertEqual("awake", controller.status())
            self.assertEqual("/usr/bin/ping", run.call_args.args[0][0])
            run.return_value.returncode = 1
            self.assertEqual("asleep_or_unreachable", controller.status())

    def test_wake_sends_three_packets_only_to_configured_broadcast(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch("socket.socket") as factory:
            client = factory.return_value.__enter__.return_value
            controller = PowerController(self.config(Path(directory) / "token"))
            self.assertEqual("wake_requested", controller.wake())
            client.setsockopt.assert_called_once_with(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self.assertEqual(3, client.sendto.call_count)
            self.assertEqual(("192.168.0.255", 9), client.sendto.call_args.args[1])

    def test_sleep_requires_matching_receiver_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("s" * 48, encoding="utf-8")
            controller = PowerController(self.config(token))
            response = mock.MagicMock()
            response.status = 202
            response.read.return_value = json.dumps({"accepted": True, "operation_id": "op"}).encode()
            response.__enter__.return_value = response
            with mock.patch("pc_power.controller.urlopen", return_value=response) as request:
                self.assertEqual("sleep_accepted", controller.sleep("op"))
                sent = request.call_args.args[0]
                self.assertEqual("Bearer " + "s" * 48, sent.headers["Authorization"])
            response.read.return_value = json.dumps({"accepted": True, "operation_id": "different"}).encode()
            with mock.patch("pc_power.controller.urlopen", return_value=response):
                with self.assertRaises(PowerActionError) as error:
                    controller.sleep("op")
            self.assertEqual("sleep_result_unknown", error.exception.code)

    def test_receiver_health_requires_positive_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("h" * 48, encoding="utf-8")
            controller = PowerController(self.config(token))
            response = mock.MagicMock()
            response.status = 200
            response.read.return_value = b'{"ready":true}'
            response.__enter__.return_value = response
            with mock.patch("pc_power.controller.urlopen", return_value=response) as request:
                self.assertTrue(controller.receiver_ready())
                self.assertEqual("http://100.92.109.109:9120/health", request.call_args.args[0].full_url)
            response.read.return_value = b'{"ready":false}'
            with mock.patch("pc_power.controller.urlopen", return_value=response):
                self.assertFalse(controller.receiver_ready())

    def test_health_is_bounded_and_missing_token_does_not_break_status(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            controller = PowerController(self.config(token))
            self.assertFalse(controller.receiver_ready())
            token.write_text("h" * 48, encoding="utf-8")
            response = mock.MagicMock()
            response.status = 200
            response.read.return_value = b'{"ready":true,"last_operation":{"state":"failed"}}'
            response.__enter__.return_value = response
            with mock.patch("pc_power.controller.urlopen", return_value=response) as request:
                self.assertEqual("failed", controller.receiver_health()["last_operation"]["state"])
                self.assertLessEqual(request.call_args.kwargs["timeout"], 1.5)


if __name__ == "__main__":
    unittest.main()
