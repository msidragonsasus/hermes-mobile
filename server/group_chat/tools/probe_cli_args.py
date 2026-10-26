"""Capture the installed CLI parser via --help, then parse without dispatching."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import runpy
import sys
from unittest.mock import patch


class ParserCaptured(BaseException):
    def __init__(self, parser):
        self.parser = parser


def probe(entrypoint):
    def capture(parser, *args, **kwargs):
        raise ParserCaptured(parser)

    original = argparse.ArgumentParser.parse_args
    previous = sys.argv
    # Even if a CLI stops using parse_args, --help cannot dispatch a query.
    sys.argv = [str(entrypoint), "--profile", "default", "chat", "--help"]
    try:
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                with patch.object(argparse.ArgumentParser, "parse_args", capture):
                    runpy.run_path(str(entrypoint), run_name="__main__")
            except ParserCaptured as captured:
                parser = captured.parser
            else:
                raise RuntimeError("CLI parser was not captured")
            legacy_rejected = False
            try:
                original(parser, ["chat", "-Q", "synthetic-parser-check"])
            except SystemExit as failure:
                legacy_rejected = failure.code == 2
            parsed = original(parser, ["chat", "-Q", "--query-file", "-"])
            if not legacy_rejected or not parsed.quiet or getattr(parsed, "query_file", None) != "-":
                raise RuntimeError("Installed CLI argument contract differs")
    finally:
        sys.argv = previous
    return {"old_command_rejected": True, "corrected_command_parsed": True, "agent_execution": "not_requested"}


if __name__ == "__main__":
    print(json.dumps(probe(sys.argv[1])))
