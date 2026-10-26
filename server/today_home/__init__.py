"""Native Hermes tool for the same saved plan displayed on Mobile Home."""


def register(ctx):
    from .today_core.agent_tools import register_tools
    register_tools(ctx)
