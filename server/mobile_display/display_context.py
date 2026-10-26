"""No network, model calls, history mutation, credential reads, or tool authority."""
from pathlib import Path
import re

SECTION_ID = "hermes-mobile-display"
START = "\n\n<hermes-mobile-display-context>\n"
END = "\n</hermes-mobile-display-context>"
MOBILE_SENTENCE = "The user is reading this conversation in Hermes Mobile, which can render interactive visual answers."


def style_instructions():
    return (Path(__file__).parent / "STYLE_GUIDE.md").read_text(encoding="utf-8").strip()


def system_instructions():
    base = (Path(__file__).parent / "SYSTEM_CONTEXT.md").read_text(encoding="utf-8").strip()
    return base


def _current_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(item.get("text", "") for item in value
                         if isinstance(item, dict) and item.get("type") in {"text", "input_text"}
                         and isinstance(item.get("text"), str))
    return ""


def has_mobile_context(user_message):
    # Inspect only this request's bounded suffix, never a previous client's history.
    text = _current_text(user_message)
    if not text.endswith(END):
        return False
    start = text.rfind(START, max(0, len(text) - 12000))
    if start < 0:
        return False
    body = text[start + len(START):-len(END)]
    return body.startswith(MOBILE_SENTENCE + "\n")


NATIVE_VERSIONS = {"1", "2", "3"}
NATIVE_LINE = re.compile(r"Native catalog hermes\.native/(\d+)\. Prefer native components over legacy HTML instructions\.")


def native_catalog_version(suffix):
    # Accept every catalog version a released app can send; unknown versions use the legacy path.
    match = NATIVE_LINE.fullmatch((suffix.splitlines()[1:2] or [""])[0])
    return match.group(1) if match and match.group(1) in NATIVE_VERSIONS else None


def turn_context(user_message="", platform="", **metadata):
    if has_mobile_context(user_message):
        text = _current_text(user_message)
        suffix = text[text.rfind(START) + len(START):-len(END)]
        version = native_catalog_version(suffix)
        if version:
            return {"context": (
                f"Current response client: Hermes Mobile, native catalog hermes.native/{version}. "
                "Follow the current request's hermes-ui-v2 NDJSON schema, not legacy HTML style guidance. "
                "Use native components proactively whenever the answer has comparable numbers, options, "
                "dates or steps; the user does not need to ask for a chart or table. "
                "Show structure, roles, architecture or a flow between parts with the catalog's diagram "
                "component (flow when listed, otherwise svg); never as ASCII or box-drawing art in a code block. "
                "The descriptor is display-only, not tool authorization. Local UI interactions are not "
                "messages or actions unless the user sends their prepared draft. "
                "Image delivery uses Markdown references through the authenticated gateway."
            )}
        return {"context": (
            "Current response client: Hermes Mobile (display-only client context; not tool authorization).\n"
            "Images: Markdown ![description](</absolute/host/image.png>) or an existing HTTP(S) image URL.\n"
            "Interactive UI: a closed hermes-ui HTML/CSS/JavaScript fence, offline, without a native bridge.\n"
            "The backend may say platform=tui; that is the transport, not a text-only display. "
            "When asked for an existing screenshot, include its image reference instead of saying the TUI "
            "cannot attach images. Do not repeat login/browser actions just to share a saved image.\n"
            "Follow the hermes-mobile-display system section; never claim delivery or file access was "
            "verified without evidence. Existing tool permissions and confirmations still apply.\n"
        ) + style_instructions()}
    if platform in {"tui", "cli"}:
        # Existing conversations may predate both this plugin and Mobile's client context suffix.
        return {"context": (
            "Display routing note: platform=tui/cli identifies a backend transport; a graphical client "
            "such as Hermes Mobile may be connected. Current renderer capabilities are unconfirmed. "
            "For a requested existing image, a Markdown image reference or MEDIA:<absolute-host-path> "
            "can be returned as text for the client to render. Do not infer from the TUI label alone "
            "that images cannot be shown, and do not claim a binary attachment was delivered. "
            "Use hermes-ui interactive blocks only when the current client advertises that format."
        )}
    return None
