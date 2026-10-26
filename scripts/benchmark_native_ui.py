"""Local fixture sizes with an explicit reference tokenizer; never calls a model/API.

python -m pip install --target output/intelligent-ui-components-20261023/tokenizer tiktoken
python scripts/benchmark_native_ui.py
The reference encoding is NOT a claim about a provider's live token accounting.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import textwrap

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output/intelligent-ui-components-20261023"
sys.path.insert(0, str(OUT / "tokenizer"))
os.environ.setdefault("TIKTOKEN_CACHE_DIR", str(OUT / "tokenizer-cache"))
try:
    import tiktoken
except ImportError:
    tiktoken = None


def load(path):
    return (ROOT / path).read_text(encoding="utf-8")


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def main():
    source = load("app/src/main/java/com/m57/hermescontrol/ui/chat/NativeUiInstructions.kt")
    descriptor = textwrap.dedent(re.search('"""(.*?)"""', source, re.S).group(1)).strip()
    previous = load("app/src/test/resources/native-ui/legacy-display-context.txt")
    baseline = module(OUT / "baseline/display_context.py", "baseline_display")
    baseline.style_instructions = lambda: load("server/mobile_display/STYLE_GUIDE.md").strip()
    current = module(ROOT / "server/mobile_display/display_context.py", "current_display")
    request = "show a simulation" + current.START + descriptor + current.END
    old_system = load("output/intelligent-ui-components-20261023/baseline/SYSTEM_CONTEXT.md").strip() + "\n\n" + baseline.style_instructions()
    new_suffix = current.START + descriptor + current.END
    old_hook = baseline.turn_context(user_message="request" + previous)["context"]
    new_hook = current.turn_context(user_message=request)["context"]
    encoding = tiktoken.get_encoding("o200k_base") if tiktoken else None

    def sizes(text):
        return {"utf8_bytes": len(text.encode("utf-8")), "chars": len(text),
                "reference_tokens": len(encoding.encode(text)) if encoding else None}

    # Real checked-in HTML savings fixture, same month-end annuity and cap;
    # native ranges/layout differ, so this is a fixture comparison, not live generation.
    old_ui = load("app/src/test/resources/rich-answers/nisa.html")
    new_ui = (OUT / "savings.jsonl").read_text(encoding="utf-8")
    report = {
        "encoding": "o200k_base" if encoding else None,
        "accounting": "reference tokenizer only; live model/provider accounting not measured",
        "sample": "checked-in savings HTML versus native descriptor; not a live generation benchmark",
        "output": {"html": sizes(old_ui), "native": sizes(new_ui)},
        "display_context": {"old_suffix": sizes(previous), "new_suffix": sizes(new_suffix),
                            "old_static": sizes(old_system), "new_static": sizes(current.system_instructions()),
                            "old_hook": sizes(old_hook), "new_hook": sizes(new_hook)},
        "input_excludes": "shared user request/history, cached-token billing and unrelated system instructions",
        "network_model_calls": 0,
        "fixed_native_examples": {name: sizes((OUT / (name + ".jsonl")).read_text(encoding="utf-8"))
                                  for name in ("savings", "comparison", "chart")},
    }
    if encoding:
        count = lambda s: len(encoding.encode(s))
        report["output_reduction_percent"] = round(100 * (1 - count(new_ui) / count(old_ui)), 2)
        report["single_turn_context_reduction_percent"] = round(100 * (1 -
            count(new_suffix + current.system_instructions() + new_hook) /
            count(previous + old_system + old_hook)), 2)
        report["five_turn_context_reference_tokens"] = {
            "old": count(old_system + old_hook + previous * 5),
            "new": count(current.system_instructions() + new_hook + new_suffix * 5),
        }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "token-comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
