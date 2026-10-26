"""Exercise the actual Windows HTTP loop with only the native sleep call replaced.

Never calls the real SetSuspendState, changes a task, or touches ProgramData.
"""
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import uuid


@unittest.skipUnless(os.name == "nt", "Windows receiver integration")
class WindowsReceiverTests(unittest.TestCase):
    def start_receiver(self, privilege_error=0):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        source = (Path(__file__).parents[1] / "windows" / "HermesPcPower.ps1").read_text(encoding="utf-8-sig")
        start = source.index("Add-Type @'")
        end = source.index("\n'@", start) + len("\n'@")
        # The temporary receiver has no P/Invoke declarations whatsoever.
        fake = """Add-Type @'
public static class HermesPowerState {
    public static int EnableShutdownPrivilege() { return PRIVILEGE; }
    public static bool SetSuspendState(bool a, bool b, bool c) { return false; }
}
'@""".replace("PRIVILEGE", str(privilege_error))
        safe_source = source[:start] + fake + source[end:]
        self.assertNotIn("DllImport", safe_source)
        script = root / "receiver.ps1"
        script.write_text(safe_source, encoding="utf-8-sig")
        self.token = "test-only-" + "x" * 48
        (root / "token").write_text(self.token, encoding="utf-8")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            self.port = sock.getsockname()[1]
        self.operations = root / "operations"
        self.command = ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", str(script), "-ListenAddress", "127.0.0.1", "-AllowedClient", "127.0.0.1",
                        "-TokenFile", str(root / "token"), "-OperationFile", str(self.operations),
                        "-Port", str(self.port)]
        self.launch()

    def launch(self):
        process = subprocess.Popen(self.command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.process = process

        def stop():
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
        self.addCleanup(stop)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if process.poll() is not None:
                self.fail("Receiver exited before listening")
            try:
                self.request("GET", "/health")
                return
            except OSError:
                time.sleep(0.1)
        self.fail("Receiver did not listen")

    def request(self, method, path, payload=None, token=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            headers = {"Authorization": "Bearer " + (self.token if token is None else token)}
            body = json.dumps(payload) if payload is not None else None
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_failed_native_sleep_is_observable_and_never_replayed(self):
        self.start_receiver()
        operation = str(uuid.uuid4())
        self.assertEqual(401, self.request("GET", "/health", token="wrong")[0])
        self.assertEqual(400, self.request("POST", "/sleep", {"operation_id": "invalid"})[0])
        self.assertEqual(202, self.request("POST", "/sleep", {"operation_id": operation})[0])
        health = self.request("GET", "/health")[1]
        self.assertTrue(health["ready"])
        self.assertEqual("failed", health["last_operation"]["state"])
        self.assertEqual(operation, health["last_operation"]["operation_id"])
        self.assertEqual(409, self.request("POST", "/sleep", {"operation_id": operation})[0])
        self.process.terminate()
        self.process.wait(timeout=5)
        self.launch()
        self.assertEqual("failed", self.request("GET", "/health")[1]["last_operation"]["state"])
        self.assertEqual(409, self.request("POST", "/sleep", {"operation_id": operation})[0])
        self.assertEqual([operation], self.operations.read_text(encoding="utf-8-sig").splitlines())

    def test_missing_privilege_is_not_reported_as_ready_or_accepted(self):
        self.start_receiver(privilege_error=1300)
        health = self.request("GET", "/health")[1]
        self.assertFalse(health["ready"])
        self.assertEqual("shutdown_privilege_missing", health["error"])
        self.assertEqual(503, self.request("POST", "/sleep", {"operation_id": str(uuid.uuid4())})[0])
        self.assertFalse(self.operations.exists())


if __name__ == "__main__":
    unittest.main()
