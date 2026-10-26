import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("probe_cli_args", Path(__file__).resolve().parents[1] / "tools/probe_cli_args.py")
probe_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe_module)


class CliParserProbeTest(unittest.TestCase):
    def test_real_parser_is_checked_without_calling_its_handler(self):
        with tempfile.TemporaryDirectory() as folder:
            script = Path(folder) / "entry.py"
            script.write_text('''import argparse
parser = argparse.ArgumentParser()
chat = parser.add_subparsers(dest="command").add_parser("chat")
chat.add_argument("-Q", "--quiet", action="store_true")
chat.add_argument("-q", "--query")
chat.add_argument("--query-file")
parser.parse_args()
raise AssertionError("A handler must never be called")
''', encoding="utf-8")
            self.assertEqual({"old_command_rejected": True, "corrected_command_parsed": True,
                              "agent_execution": "not_requested"}, probe_module.probe(script))

    def test_query_argument_without_stdin_file_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            script = Path(folder) / "entry.py"
            script.write_text('''import argparse
parser = argparse.ArgumentParser()
chat = parser.add_subparsers(dest="command").add_parser("chat")
chat.add_argument("-Q", "--quiet", action="store_true")
chat.add_argument("-q", "--query")
parser.parse_args()
raise AssertionError("A handler must never be called")
''', encoding="utf-8")
            with self.assertRaises(SystemExit):
                probe_module.probe(script)

    def test_incompatible_parser_cannot_trigger_a_handler(self):
        with tempfile.TemporaryDirectory() as folder:
            script = Path(folder) / "entry.py"
            script.write_text('''import argparse
parser = argparse.ArgumentParser()
parser.parse_args()
raise AssertionError("A handler must never be called")
''', encoding="utf-8")
            with self.assertRaises(SystemExit):
                probe_module.probe(script)
