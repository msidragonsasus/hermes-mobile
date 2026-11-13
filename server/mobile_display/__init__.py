"""Agent-side display knowledge, using Hermes' native prompt-section and turn hooks."""
from functools import partial

from .display_context import NATIVE_VERSIONS, SECTION_ID, surface_note, system_instructions, turn_context


def register(ctx):
    section = getattr(ctx, "register_system_prompt_section", None)
    hook = getattr(ctx, "register_hook", None)
    if not callable(section) or not callable(hook):
        raise RuntimeError("mobile_display requires system prompt sections and pre_llm_call")
    section(SECTION_ID, system_instructions(), position="after_memory", max_chars=4000)
    register_surface = getattr(ctx, "register_client_surface", None)
    supported = callable(register_surface)
    if supported:
        for version in sorted(NATIVE_VERSIONS):
            register_surface(f"mobile-v{version}", surface_note(version))
    hook("pre_llm_call", partial(turn_context, surface_supported=supported))
