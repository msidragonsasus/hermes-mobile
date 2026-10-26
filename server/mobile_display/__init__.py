"""Agent-side display knowledge, using Hermes' native prompt-section and turn hooks."""
from .display_context import SECTION_ID, system_instructions, turn_context


def register(ctx):
    section = getattr(ctx, "register_system_prompt_section", None)
    hook = getattr(ctx, "register_hook", None)
    if not callable(section) or not callable(hook):
        raise RuntimeError("mobile_display requires system prompt sections and pre_llm_call")
    section(SECTION_ID, system_instructions(), position="after_memory", max_chars=4000)
    hook("pre_llm_call", turn_context)
