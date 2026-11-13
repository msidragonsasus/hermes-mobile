"""Explicit activation uses temporary owned files; no live config or model."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from career_core import mobile_workbench

EXECUTION = {'provider': 'synthetic', 'model': 'synthetic-editor', 'reasoning_effort': 'default'}


class ActivationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.config = self.home / 'career-app-editor.json'
        self.workspace = self.home / 'app-workspaces/career.mypage/default/one'
        self.workspace.mkdir(parents=True, mode=0o700)
        for directory in self.workspace.parents:
            if directory == self.home.parent:
                break
            directory.chmod(0o700)
        self.value = dict(schema_version=1, enabled=True, company_id='one', profile='default',
                          execution=EXECUTION, auto_run=True)

    def save(self, value=None):
        self.config.write_text(json.dumps(self.value if value is None else value), encoding='utf-8')
        self.config.chmod(0o600)

    def build(self):
        from career_core.mobile_activation import configured_workbench
        return configured_workbench(self.home)

    def test_missing_or_disabled_configuration_has_no_workspace_or_model_effect(self):
        from career_core.mobile_activation import configured_workbench
        with patch('career_core.coach_runtime.make_agent') as forbidden:
            self.assertIsNone(configured_workbench(self.home))
            self.save({'schema_version': 1, 'enabled': False})
            self.assertIsNone(self.build())
            forbidden.assert_not_called()
        self.assertEqual([], list(self.workspace.iterdir()))

    def test_exact_configuration_constructs_only_owned_code_workspace_without_model(self):
        self.save()
        with patch('career_core.coach_runtime.make_agent') as forbidden:
            bench = self.build()
            self.assertIsInstance(bench, mobile_workbench.AppWorkbench)
            self.assertTrue(bench.matches('one', 'default'))
            self.assertFalse(bench.matches('two', 'default'))
            self.assertEqual(self.workspace / 'code-state.sqlite', bench.db_path)
            self.assertEqual(EXECUTION, bench.editor.execution)
            self.assertTrue(bench.editor.auto_run)
            bench.snapshot('one', 'default')
            bench.editor.summary('one', 'default')
            forbidden.assert_not_called()
        self.assertEqual(0o600, bench.db_path.stat().st_mode & 0o777)

    def test_invalid_configuration_fails_closed_without_allocating(self):
        invalid = [dict(self.value, root='/unreviewed'), dict(self.value, company_id='../two'),
                   dict(self.value, profile='other/one'), dict(self.value, auto_run='true'),
                   dict(self.value, enabled=1), dict(self.value, schema_version=True),
                   dict(self.value, execution=dict(EXECUTION, provider='auto')),
                   dict(self.value, execution=dict(EXECUTION, model=' synthetic-editor'))]
        for value in invalid:
            with self.subTest(value=value):
                self.save(value)
                with self.assertRaises(ValueError): self.build()
                self.assertEqual([], list(self.workspace.iterdir()))

    def test_rejects_missing_shared_linked_or_foreign_workspace_and_config(self):
        self.save()
        self.workspace.rmdir()
        with self.assertRaises(ValueError): self.build()
        self.assertFalse(self.workspace.exists())
        self.workspace.mkdir(mode=0o700)
        self.workspace.chmod(0o750)
        with self.assertRaises(ValueError): self.build()
        self.workspace.chmod(0o700)
        self.config.chmod(0o640)
        with self.assertRaises(ValueError): self.build()
        self.config.chmod(0o600)
        other = self.home / 'other'
        other.mkdir(mode=0o700)
        self.workspace.rmdir(); self.workspace.symlink_to(other, target_is_directory=True)
        with self.assertRaises(ValueError): self.build()
        self.assertEqual([], list(other.iterdir()))

    def test_rejects_unrelated_files_and_linked_database_before_sqlite(self):
        self.save()
        marker = self.workspace / 'unrelated-private-data'
        marker.write_text('synthetic-preserve', encoding='utf-8')
        with self.assertRaises(ValueError): self.build()
        self.assertEqual('synthetic-preserve', marker.read_text())
        marker.unlink()
        target = self.home / 'preserved.sqlite'
        target.write_bytes(b'synthetic-preserve')
        os.link(target, self.workspace / 'code-state.sqlite')
        with self.assertRaises(ValueError): self.build()
        self.assertEqual(b'synthetic-preserve', target.read_bytes())

    def test_rejects_duplicate_keys_symlink_config_foreign_owner_and_sidecars(self):
        self.save()
        self.config.write_text('{"schema_version":1,"enabled":false,"enabled":true}', encoding='utf-8')
        with self.assertRaises(ValueError): self.build()
        self.save()
        with patch('career_core.mobile_activation.os.geteuid', return_value=os.geteuid() + 1):
            with self.assertRaises(ValueError): self.build()
        other = self.home / 'other-config.json'
        self.config.rename(other); self.config.symlink_to(other)
        with self.assertRaises(ValueError): self.build()
        self.config.unlink(); other.rename(self.config)
        sidecar = self.workspace / 'code-state.sqlite-journal'
        sidecar.symlink_to(other)
        with self.assertRaises(ValueError): self.build()
        self.assertFalse((self.workspace / 'code-state.sqlite').exists())

    def test_configured_runner_dispatches_once_only_on_explicit_post(self):
        import test_mobile_workbench as fixture
        case = fixture.WorkbenchTests()
        case.setUp()
        self.addCleanup(case.doCleanups)
        self.save()
        bench = self.build()
        case.workbench = bench
        case.client = case.make_client(bench)
        factory_calls = []
        class Agent:
            def run_conversation(agent, goal, **kwargs):
                return {'final_response': json.dumps({'files': bench.snapshot('one', 'default')['files']})}
            def close(agent): pass
        def factory(*args, **kwargs):
            factory_calls.append((args, kwargs)); return Agent()
        with patch('career_core.coach_runtime.make_agent', side_effect=factory):
            before = case.snapshot()
            self.assertEqual([], factory_calls)
            payload = dict(protocol_version=1, action='propose', operation_id='synthetic-config-0001',
                           expected_active=before['active'], revision=before['revision'], goal='Synthetic request')
            receipt = case.post(payload)
            self.assertEqual('done', receipt['state'])
            self.assertEqual(1, len(factory_calls))
            self.assertEqual(receipt, case.post(payload))
            self.assertEqual(1, len(factory_calls))
            self.assertEqual('proposed', case.client.get(case.base + '/editor-job').json()['job']['state'])
            self.assertEqual(403, case.client.get(case.base.replace('/one', '/two')).status_code)
            self.assertEqual(403, case.client.get(case.base + '?profile=other').status_code)
        self.assertFalse(factory_calls[0][1]['tools_enabled'])
        self.assertEqual(EXECUTION, factory_calls[0][1]['execution'])

    def test_dashboard_entry_consumes_configuration_on_actual_mount_path(self):
        self.save()
        entry = Path(__file__).resolve().parents[1] / 'dashboard/plugin_api.py'
        with patch.dict(os.environ, {'HERMES_HOME': str(self.home)}), \
             patch('career_core.deployment.prepare', return_value=None), \
             patch('career_core.deployment.attach'), \
             patch('career_core.api.create_router', return_value=object()) as create, \
             patch('career_core.coach_runtime.make_agent') as forbidden:
            spec = importlib.util.spec_from_file_location('synthetic_phase3_mount', entry)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            bench = create.call_args.kwargs.get('mobile_workbench')
            self.assertIsInstance(bench, mobile_workbench.AppWorkbench)
            self.assertEqual(EXECUTION, bench.editor.execution)
            forbidden.assert_not_called()
