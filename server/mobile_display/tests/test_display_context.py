"""Run with python -m unittest discover -s server/mobile_display/tests -v."""
import ast
import importlib.util
import os
from pathlib import Path
import re
import sys
import tempfile
import textwrap
import threading
import types
import unittest
from unittest.mock import patch


PLUGIN = Path(__file__).resolve().parents[1]
REPO = PLUGIN.parents[1]
SPEC = importlib.util.spec_from_file_location(
    "mobile_display_under_test", PLUGIN / "__init__.py",
    submodule_search_locations=[str(PLUGIN)],
)
plugin = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = plugin
SPEC.loader.exec_module(plugin)
display = sys.modules[SPEC.name + ".display_context"]
PREFLIGHT_SPEC = importlib.util.spec_from_file_location("display_preflight", PLUGIN / "preflight.py")
preflight = importlib.util.module_from_spec(PREFLIGHT_SPEC)
PREFLIGHT_SPEC.loader.exec_module(preflight)


def mobile_request():
    """Read the actual Kotlin wire descriptor instead of duplicating its wording."""
    source = (REPO / "app/src/main/java/com/m57/hermescontrol/ui/chat/RichAnswerPrompt.kt").read_text(encoding="utf-8")
    marker = re.search(r'const val MARKER = "(.*?)"', source).group(1).replace("\\n", "\n")
    end = re.search(r'const val END = "(.*?)"', source).group(1).replace("\\n", "\n")
    chunks = re.findall(r'"""(.*?)"""\.trimIndent\(\)', source, re.DOTALL)
    instructions = "\n".join(textwrap.dedent(chunk).strip() for chunk in chunks)
    return "保存済みのスクショを見せて" + marker + instructions + end


class RecordingContext:
    def __init__(self):
        self.sections = []
        self.hooks = {}

    def register_system_prompt_section(self, *args, **kwargs):
        self.sections.append((args, kwargs))

    def register_hook(self, name, callback):
        self.hooks[name] = callback


class DisplayContextTests(unittest.TestCase):
    def test_surface_catalogs_match_android_versions_and_fit_host_limit(self):
        source = (REPO / "app/src/main/java/com/m57/hermescontrol/ui/chat/NativeUiInstructions.kt").read_text(encoding="utf-8")
        descriptors = [textwrap.dedent(chunk).strip() for chunk in re.findall(r'"""(.*?)"""', source, re.DOTALL)]
        for version in sorted(display.NATIVE_VERSIONS):
            filename = "NATIVE_CATALOG.md" if version == "3" else f"NATIVE_CATALOG_V{version}.md"
            descriptor = (PLUGIN / filename).read_text(encoding="utf-8").strip()
            self.assertIn(descriptor, descriptors)
            self.assertIn(f"Native catalog hermes.native/{version}.", descriptor)
            note = display.surface_note(version)
            self.assertIn(descriptor, note)
            self.assertIn("display-only, not tool authorization", note)
            self.assertLessEqual(len(note), 6000)

    def test_surface_host_has_one_note_path_per_turn(self):
        ctx = RecordingContext()
        surfaces = {}
        ctx.register_client_surface = lambda name, note: surfaces.setdefault(name, note)
        plugin.register(ctx)
        self.assertEqual(set(surfaces), {"mobile-v1", "mobile-v2", "mobile-v3"})
        hook = ctx.hooks["pre_llm_call"]
        # New client: the core's registered note is sufficient. No unknown-client hook note.
        self.assertIsNone(hook(user_message="show UI", platform="tui"))
        # Old client: the suffix remains the only source of the current-client hook note.
        old = "show UI" + display.START + (PLUGIN / "NATIVE_CATALOG.md").read_text(encoding="utf-8").strip() + display.END
        self.assertEqual(hook(user_message=old, platform="tui"), {"context": display.native_turn_context("3")})
        # No surface and no marker never asserts a renderer from history.
        self.assertIsNone(hook(user_message="plain", platform="cli", conversation_history=[old]))

    def test_current_android_wire_is_recognized(self):
        self.assertTrue(display.has_mobile_context(mobile_request()))

    def test_closed_suffix_only(self):
        for text in (mobile_request() + " trailing", mobile_request()[:-1],
                     "<hermes-mobile-display-context>\n" + display.MOBILE_SENTENCE,
                     display.START + "Unknown renderer\n" + display.END):
            with self.subTest(text=text[-30:]):
                self.assertFalse(display.has_mobile_context(text))

    def test_suffix_search_is_bounded(self):
        text = display.START + display.MOBILE_SENTENCE + "\n" + "a" * 13000 + display.END
        self.assertFalse(display.has_mobile_context(text))

    def test_long_user_message_does_not_hide_valid_suffix(self):
        self.assertTrue(display.has_mobile_context("a" * 100000 + mobile_request()))

    def test_multimodal_current_text(self):
        self.assertTrue(display.has_mobile_context([
            {"type": "image_url", "image_url": {"url": "https://example.invalid/private"}},
            {"type": "input_text", "text": mobile_request()},
        ]))

    def test_image_fields_are_not_display_descriptors(self):
        self.assertFalse(display.has_mobile_context([{"type": "image_url", "text": mobile_request()}]))
        for value in (None, {}, 1, [{"type": "text", "text": None}]):
            self.assertFalse(display.has_mobile_context(value))

    def test_mobile_hook_overrides_transport_wording(self):
        result = display.turn_context(user_message=mobile_request(), platform="tui", model="unused")
        self.assertIn("Current response client: Hermes Mobile", result["context"])
        self.assertIn("![description]", result["context"])
        self.assertIn("hermes-ui", result["context"])
        self.assertIn("Do not repeat login/browser", result["context"])

    def test_new_and_continued_mobile_turns_share_the_canonical_style_guide(self):
        guide = (PLUGIN / "STYLE_GUIDE.md").read_text(encoding="utf-8").strip()
        continued = display.turn_context(user_message=mobile_request(), platform="tui")["context"]
        self.assertNotIn(guide, display.system_instructions())
        self.assertIn(guide, continued)
        self.assertNotIn("controls below", continued)
        self.assertLessEqual(len(continued), 4000)
        with patch.object(display, "style_instructions", return_value="updated-style-sentinel"):
            self.assertNotIn("updated-style-sentinel", display.system_instructions())
            self.assertIn("updated-style-sentinel", display.turn_context(user_message=mobile_request())["context"])

    def test_past_mobile_history_does_not_establish_current_client(self):
        result = display.turn_context(user_message="hello", platform="tui",
                                      conversation_history=[{"role": "user", "content": mobile_request()}])
        self.assertIn("capabilities are unconfirmed", result["context"])
        self.assertNotIn("Current response client: Hermes Mobile", result["context"])

    def test_unknown_non_terminal_client_gets_no_mobile_assumption(self):
        for platform in ("", "slack", "telegram", None):
            self.assertIsNone(display.turn_context(user_message="hello", platform=platform))

    def test_context_does_not_echo_private_input(self):
        secret = "PRIVATE-SENTINEL-DO-NOT-ECHO"
        result = display.turn_context(user_message=secret + mobile_request(), token=secret,
                                      conversation_history=[secret])
        self.assertNotIn(secret, result["context"])

    def test_no_client_state_leaks_between_turns(self):
        first = display.turn_context(user_message=mobile_request(), platform="tui")
        self.assertIsNone(display.turn_context(user_message="hello", platform="slack"))
        self.assertEqual(first, display.turn_context(user_message=mobile_request(), platform="tui"))

    def test_registers_native_static_section_and_turn_hook(self):
        ctx = RecordingContext()
        plugin.register(ctx)
        self.assertEqual(set(ctx.hooks), {"pre_llm_call"})
        self.assertEqual(len(ctx.sections), 1)
        args, kwargs = ctx.sections[0]
        self.assertEqual(args[0], "hermes-mobile-display")
        self.assertEqual(args[1], display.system_instructions())
        self.assertEqual(kwargs, {"position": "after_memory", "max_chars": 4000})

    def test_unsupported_host_fails_before_registering_anything(self):
        for missing in ("register_hook", "register_system_prompt_section"):
            ctx = RecordingContext()
            setattr(ctx, missing, None)
            with self.assertRaises(RuntimeError):
                plugin.register(ctx)
            self.assertEqual(ctx.sections, [])
            self.assertEqual(ctx.hooks, {})

    def test_static_section_fits_host_budget_and_has_output_contract(self):
        text = display.system_instructions()
        self.assertGreater(len(text), 100)
        self.assertLessEqual(len(text), 4000)
        for required in ("![description](</absolute/host/image.png>)", "hermes-ui",
                         "240,000", "no filesystem", "Do not repeat browser", "renderer is unknown"):
            self.assertIn(required, text)
        self.assertNotIn("hermes-plugin-sections:start", text)

    def test_native_descriptor_selects_native_without_repeating_catalog(self):
        descriptor = (PLUGIN / "NATIVE_CATALOG.md").read_text(encoding="utf-8").strip()
        request = "show UI" + display.START + descriptor + display.END
        result = display.turn_context(user_message=request)["context"]
        self.assertIn("hermes-ui-v2", result)
        self.assertNotIn("viz-controls", result)
        self.assertNotIn("monthlyMax", result)
        self.assertNotIn("hermes-ui-v2", display.turn_context(user_message="hello", platform="tui")["context"])
        source = (REPO / "app/src/main/java/com/m57/hermescontrol/ui/chat/NativeUiInstructions.kt").read_text(encoding="utf-8")
        chunk = re.search(r'"""(.*?)"""', source, re.DOTALL).group(1)
        self.assertEqual(textwrap.dedent(chunk).strip(), descriptor)

    def test_previous_and_current_native_catalog_versions_both_select_native(self):
        descriptor = (PLUGIN / "NATIVE_CATALOG.md").read_text(encoding="utf-8").strip()
        self.assertIn("Native catalog hermes.native/3.", descriptor)
        for version in ("1", "2", "3"):
            text = descriptor.replace("hermes.native/3.", f"hermes.native/{version}.")
            result = display.turn_context(user_message="show UI" + display.START + text + display.END)["context"]
            self.assertIn(f"native catalog hermes.native/{version}.", result)
            self.assertNotIn("viz-controls", result)
            # Diagrams go through a component on every native version, never ASCII art.
            self.assertIn("flow when listed, otherwise svg", result)
            self.assertIn("never as ASCII", result)
        unknown = descriptor.replace("hermes.native/3.", "hermes.native/9.")
        result = display.turn_context(user_message="show UI" + display.START + unknown + display.END)["context"]
        self.assertNotIn("native catalog hermes.native/9", result)

    def test_diagram_rule_is_in_catalog_and_static_section(self):
        descriptor = (PLUGIN / "NATIVE_CATALOG.md").read_text(encoding="utf-8")
        self.assertIn("flow: nodes:[{id,label,sub,accent}]", descriptor)
        self.assertIn("Never draw diagrams as ASCII", descriptor)
        self.assertIn("ASCII or box-drawing art", display.system_instructions())

    def test_android_and_agent_share_the_same_style_guide(self):
        source = (REPO / "app/src/main/java/com/m57/hermescontrol/ui/chat/IntelligentUiStyle.kt").read_text(encoding="utf-8")
        chunk = re.search(r'val authoringInstructions\s*=\s*"""(.*?)"""\.trimIndent\(\)', source, re.DOTALL).group(1)
        self.assertEqual(textwrap.dedent(chunk).strip(), (PLUGIN / "STYLE_GUIDE.md").read_text(encoding="utf-8").strip())


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def compatible_source(self):
        self.write("hermes_cli/plugins.py", "class PluginContext:\n def register_hook(self): pass\n def register_system_prompt_section(self): pass\n")
        self.write("hermes_cli/plugins_dispatch.py", "class PluginDispatchMixin:\n def invoke_hook(self): pass\n def render_system_prompt_sections(self): pass\n")
        self.write("agent/system_prompt.py", "def build():\n return plugins.render_system_prompt_sections({})\n")

    def test_missing_source_reports_unsupported_not_installed(self):
        result = preflight.inspect_contract(self.root)
        self.assertEqual(result["state"], "unsupported")
        self.assertIn("agent/system_prompt.py", result["missing"])
        self.assertFalse(result["installed"])
        self.assertFalse(result["model_called"])

    def test_full_source_contract_is_ready_without_executing_it(self):
        self.compatible_source()
        self.write("agent/system_prompt.py", "raise RuntimeError('must not execute')\ndef build():\n return render_system_prompt_sections({})\n")
        before = {str(p): p.read_bytes() for p in self.root.rglob("*.py")}
        result = preflight.inspect_contract(self.root)
        self.assertFalse(result["client_surfaces"])
        self.assertEqual(result["display_mode"], "legacy_suffix")
        self.assertEqual(result["state"], "ready")
        self.assertEqual(result["scope"], "source_contract_only")
        self.assertEqual(before, {str(p): p.read_bytes() for p in self.root.rglob("*.py")})

    def test_core_must_consume_registered_section(self):
        self.compatible_source()
        self.write("agent/system_prompt.py", "def build(): return 'no plugin sections'\n")
        self.assertIn("agent.system_prompt:render_system_prompt_sections", preflight.inspect_contract(self.root)["missing"])

    def test_broken_source_fails_closed(self):
        self.compatible_source()
        self.write("hermes_cli/plugins.py", "class ???")
        result = preflight.inspect_contract(self.root)
        self.assertEqual(result["state"], "unsupported")
        self.assertIn("hermes_cli/plugins.py:unreadable", result["missing"])

    def test_split_context_module_reports_optional_surface_support(self):
        self.compatible_source()
        self.write("hermes_cli/plugins_context.py", "class PluginContext:\n def register_hook(self): pass\n def register_system_prompt_section(self): pass\n def register_client_surface(self): pass\n")
        result = preflight.inspect_contract(self.root)
        self.assertEqual(result["state"], "ready")
        self.assertTrue(result["client_surfaces"])
        self.assertEqual(result["display_mode"], "surface")


@unittest.skipUnless(os.environ.get("HERMES_AGENT_CONTRACT_ROOT"), "native source contract root not provided")
class NativeHostContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(os.environ["HERMES_AGENT_CONTRACT_ROOT"])
        # Load the actual stdlib-only host dispatch implementation, never the agent/LLM.
        sys.path.insert(0, str(cls.root))
        import hermes_cli.plugins_dispatch as dispatch
        cls.dispatch = dispatch
        path = cls.root / "hermes_cli/plugins_context.py"
        if not path.exists():
            path = cls.root / "hermes_cli/plugins.py"
        source = ast.parse(path.read_text(encoding="utf-8-sig"))
        context = next(n for n in source.body if isinstance(n, ast.ClassDef) and n.name == "PluginContext")
        method = next(n for n in context.body if isinstance(n, ast.FunctionDef) and n.name == "register_system_prompt_section")
        # Execute the native registrar unchanged, with its original dispatch globals.
        namespace = dict(vars(dispatch))
        if path.name == "plugins_context.py":
            import hermes_cli.plugins as host_plugins
            namespace["_facade"] = lambda: host_plugins
            cls.host_plugins = host_plugins
        namespace["PluginRegistration"] = object
        exec(compile(ast.Module(body=[method], type_ignores=[]), "native_system_prompt_registrar", "exec"), namespace)
        cls.native_register = namespace[method.name]

    @classmethod
    def tearDownClass(cls):
        sys.path.remove(str(cls.root))

    def context(self):
        if hasattr(self, "host_plugins"):
            manager = self.host_plugins.PluginManager()
            manager._discovered = True
            ctx = self.host_plugins.PluginContext(
                self.host_plugins.PluginManifest(name="mobile_display", key="mobile_display", source="user"), manager,
            )
            plugin.register(ctx)
            self.addCleanup(manager.unload)
            return ctx

        class Manager(self.dispatch.PluginDispatchMixin):
            def __init__(self):
                self._system_prompt_sections = {}
                self._hooks = {}
                self._hook_timeout_lock = threading.Lock()
                self._hook_timeout_suppressed_until = {}
                self._hook_running_callbacks = {}
                self._hook_abandoned = {}
                self._hook_failures_reported = set()
                self._hook_timeout_suppression_seconds = 60

        class Context:
            plugin_id = "mobile_display"
            register_system_prompt_section = type(self).native_register

            def __init__(self):
                self._manager = Manager()

            def _register_entry(self, kind, key, mapping, entry, *args, **kwargs):
                mapping[key] = entry

            def register_hook(self, name, callback):
                self._manager._hooks.setdefault(name, []).append(callback)

        ctx = Context()
        plugin.register(ctx)
        return ctx

    def test_native_surface_registry_and_legacy_hook_are_complementary(self):
        if not hasattr(self, "host_plugins"):
            self.skipTest("older native host uses the suffix compatibility path")
        from gateway.session_surface import admit_surface, client_surfaces, surface_note
        ctx = self.context()
        with patch.object(self.host_plugins, "get_plugin_manager", return_value=ctx._manager):
            for version in sorted(display.NATIVE_VERSIONS):
                name = f"mobile-v{version}"
                self.assertIn(name, client_surfaces())
                committed = admit_surface({"surface": name})["surface_v1"]
                self.assertEqual(surface_note(committed), display.surface_note(version))
            self.assertEqual(ctx._manager.invoke_hook("pre_llm_call", user_message="new client", platform="tui"), [])
            self.assertEqual(len(ctx._manager.invoke_hook("pre_llm_call", user_message=mobile_request(), platform="tui")), 1)
            ctx._manager.unload()
            self.assertNotIn("mobile-v3", client_surfaces())

    def test_actual_registrar_renderer_and_persistence_container(self):
        ctx = self.context()
        sections = ctx._manager.render_system_prompt_sections({"platform": "tui"})
        self.assertEqual(len(sections), 1)
        self.assertEqual(sections[0].id, display.SECTION_ID)
        self.assertEqual(sections[0].content, display.system_instructions())
        prompt = self.dispatch.format_system_prompt_sections(sections)
        self.assertIn("## Plugin Context: hermes-mobile-display", prompt)
        self.assertIn("hermes-plugin-sections:start", prompt)

    def test_actual_bounded_dispatch_on_continued_mobile_turn(self):
        ctx = self.context()
        stub = types.ModuleType("hermes_cli.plugins")
        stub._resolve_hook_callback_timeout = lambda: 2.0
        with patch.dict(sys.modules, {"hermes_cli.plugins": stub}):
            result = ctx._manager.invoke_hook("pre_llm_call", user_message=mobile_request(),
                                              platform="tui", is_first_turn=False,
                                              conversation_history=[], session_id="test-no-provider")
            unknown = ctx._manager.invoke_hook("pre_llm_call", user_message="hello", platform="slack")
        self.assertEqual(len(result), 1)
        self.assertIn("Current response client: Hermes Mobile", result[0]["context"])
        self.assertEqual(unknown, [])

    def test_existing_section_collision_does_not_overwrite_host_state(self):
        ctx = self.context()
        original = ctx._manager._system_prompt_sections[display.SECTION_ID]
        with self.assertRaises(ValueError):
            plugin.register(ctx)
        self.assertIs(ctx._manager._system_prompt_sections[display.SECTION_ID], original)
        self.assertEqual(len(ctx._manager._hooks["pre_llm_call"]), 1)


if __name__ == "__main__":
    unittest.main()
