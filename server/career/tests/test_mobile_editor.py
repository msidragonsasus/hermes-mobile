"""Mocked editing jobs, real interpreted policies, existing scoped API/queue.

No actual Hermes factory, credentials, model/network or production workspace.
"""
from concurrent.futures import ThreadPoolExecutor
import copy,json,threading,time,unittest
from unittest.mock import patch

from career_core.api import create_router
from career_core.mobile_workbench import AppWorkbench, RestrictedRuntime, digest
from career_core.mobile_editor import HermesEditorRunner
from career_core.mobile_bot_policy import kind, parse
from career_core.store import CareerError, encoded
from career_core.mypage_bot import connection_id
import test_mobile_workbench as fixture

POLICY = '''def enqueue(adapter, company_id, profile):
    state = adapter.read_state(company_id, profile)
    if state == "auth_required":
        return adapter.refuse("mypage_auth_required")
    if state in ("queued", "running", "collecting", "analyzing"):
        return adapter.refuse("mypage_busy")
    return adapter.enqueue_existing(company_id, profile)
'''
EXECUTION = {'provider':'synthetic', 'model':'synthetic-editor', 'reasoning_effort':'default'}


class FakeRunner:
    def __init__(self):
        self.calls = []
        self.cancelled = []
        self.on_run = lambda job, cancelled: None
    def run(self, job, cancelled):
        self.calls.append(copy.deepcopy(job));self.on_run(job, cancelled)
        files = copy.deepcopy(job['files'])
        screen=json.loads(files['mobile_screen.json']);screen['title']='Natural request proposal'
        files['mobile_screen.json']=json.dumps(screen,ensure_ascii=False)
        files['mypage_handler.py']=POLICY
        return files
    def cancel(self, identity):
        self.cancelled.append(identity)


class EditorTests(unittest.TestCase):
    def setUp(self):
        fixture.WorkbenchTests.setUp(self)
        self.runner = FakeRunner()
        self.enable(auto=False)
    make_client = fixture.WorkbenchTests.make_client
    snapshot = fixture.WorkbenchTests.snapshot
    payload = fixture.WorkbenchTests.payload
    post = fixture.WorkbenchTests.post
    def enable(self, auto=False, runner=True):
        self.workbench=AppWorkbench(self.root,'one','default',editor_runner=self.runner if runner else None,
                                   editor_execution=EXECUTION if runner else None,editor_auto_run=auto)
        self.client=self.make_client(self.workbench)
    def propose(self, goal='ログイン待ちと確認中を分け、確認の重複を止める'):
        payload=self.payload('propose',goal=goal);receipt=self.post(payload)
        return payload,receipt
    def summary(self):
        response=self.client.get(self.base+'/editor-job');self.assertEqual(200,response.status_code)
        return response.json()

    def test_acceptance_is_durable_and_never_executes_without_runner(self):
        self.enable(runner=False)
        with patch('career_core.coach_runtime.make_agent') as forbidden:
            payload,receipt=self.propose()
            self.assertEqual('done',receipt['state'])
            self.assertEqual('awaiting_executor',self.summary()['job']['state'])
            self.assertFalse(self.snapshot()['draft'])
            self.workbench.editor.run(payload['operation_id'])
            forbidden.assert_not_called()

    def test_natural_job_replaces_bot_logic_then_review_test_apply_and_real_queue(self):
        before=self.snapshot()['active'];payload,_=self.propose()
        self.workbench.editor.run(payload['operation_id'])
        candidate=self.snapshot()
        self.assertEqual('policy',kind(candidate['files']['mypage_handler.py']))
        self.assertIn('mypage_auth_required',candidate['diff'])
        self.assertFalse(candidate['tested']);self.assertEqual(before,candidate['active'])
        self.assertEqual(digest(candidate['files']),self.summary()['job']['proposal_hash'])
        self.assertEqual('test_required',self.post(self.payload('apply'))['code'])
        self.assertEqual('done',self.post(self.payload('test'))['state'])
        self.assertEqual('done',self.post(self.payload('apply'))['state'])
        with self.store.transaction() as db:
            db.execute('UPDATE career_mypage_connections SET status=? WHERE id=?',(encoded({'state':'auth_required'}),connection_id('one','default')))
        check=dict(protocol_version=1,company_id='one',release_id=self.snapshot()['active'],operation_id='natural-policy-check-0001')
        response=self.client.post('/api/plugins/career/mobile/actions/mypage.check',json=check)
        self.assertEqual(409,response.status_code);self.assertEqual('mypage_auth_required',response.json()['detail']['code'])
        with self.store.connect() as db:self.assertEqual(0,db.execute('SELECT COUNT(*) FROM career_mypage_runs').fetchone()[0])
        with self.store.transaction() as db:
            db.execute('UPDATE career_mypage_connections SET status=? WHERE id=?',(encoded({'state':'idle'}),connection_id('one','default')))
        check['operation_id']='natural-policy-check-0002'
        self.assertEqual(200,self.client.post('/api/plugins/career/mobile/actions/mypage.check',json=check).status_code)
        self.assertEqual(EXECUTION,self.runner.calls[0]['execution'])

    def test_review_hash_and_test_apply_rollback_history_are_exact(self):
        payload,_=self.propose();self.workbench.editor.run(payload['operation_id'])
        self.post(self.payload('test'));candidate=self.snapshot();hash_value=digest(candidate['files'])
        self.assertEqual('tested',self.summary()['job']['state'])
        self.assertEqual(hash_value,self.summary()['job']['tested_hash'])
        self.assertEqual('review_changed',self.post(self.payload('apply_reviewed',review_hash='0'*64))['code'])
        self.assertEqual('done',self.post(self.payload('apply_reviewed',review_hash=hash_value))['state'])
        self.assertEqual('applied',self.summary()['job']['state'])
        self.assertEqual(hash_value,self.summary()['job']['applied_hash'])
        self.post(self.payload('rollback'));self.assertEqual('rolled_back',self.summary()['job']['state'])

    def test_manual_correction_invalidates_model_candidate_test_hash(self):
        payload,_=self.propose();self.workbench.editor.run(payload['operation_id']);self.post(self.payload('test'))
        files=self.snapshot()['files'];files['mypage_handler.py']=files['mypage_handler.py'].replace('"auth_required"','"not_connected"')
        self.post(self.payload('edit',files=files));self.assertEqual('proposed',self.summary()['job']['state'])
        self.assertIsNone(self.summary()['job']['tested_hash'])
        self.post(self.payload('cancel'));self.assertEqual('discarded',self.summary()['job']['state'])

    def test_post_background_runs_only_explicit_mock_configuration(self):
        self.enable(auto=True);payload,receipt=self.propose()
        self.assertEqual('done',receipt['state']);self.assertEqual(1,len(self.runner.calls))
        self.assertEqual('proposed',self.summary()['job']['state'])
        self.assertEqual(receipt,self.post(payload));self.assertEqual(1,len(self.runner.calls))

    def test_concurrent_worker_claim_is_at_most_once(self):
        payload,_=self.propose();self.runner.on_run=lambda job,cancelled:time.sleep(.05)
        with ThreadPoolExecutor(max_workers=6) as pool:list(pool.map(lambda _:self.workbench.editor.run(payload['operation_id']),range(6)))
        self.assertEqual(1,len(self.runner.calls));self.assertTrue(self.snapshot()['draft'])

    def test_different_request_while_pending_cannot_overwrite_goal(self):
        payload,_=self.propose()
        _,second=self.propose('Other request')
        self.assertEqual('editor_busy',second['code']);self.assertEqual(payload['operation_id'],self.summary()['job']['id'])

    def test_cancel_running_job_interrupts_and_fences_late_proposal(self):
        payload,_=self.propose();started=threading.Event();release=threading.Event()
        self.runner.on_run=lambda job,cancelled:(started.set(),release.wait(5))
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(self.workbench.editor.run,payload['operation_id']);self.assertTrue(started.wait(5))
            self.assertEqual('done',self.post(self.payload('cancel_editor'))['state'])
            self.assertEqual([payload['operation_id']],self.runner.cancelled)
            release.set();future.result(timeout=5)
        self.assertEqual('cancelled',self.summary()['job']['state']);self.assertFalse(self.snapshot()['draft'])

    def test_restart_never_blindly_resumes_a_running_model_job(self):
        payload,_=self.propose();started=threading.Event();release=threading.Event()
        self.runner.on_run=lambda job,cancelled:(started.set(),release.wait(5))
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(self.workbench.editor.run,payload['operation_id']);self.assertTrue(started.wait(5))
            new_runner=FakeRunner();restarted=AppWorkbench(self.root,'one','default',editor_runner=new_runner,editor_execution=EXECUTION)
            self.assertEqual('running',restarted.editor.summary('one','default')['job']['state'])
            restarted.editor.run(payload['operation_id']);self.assertEqual([],new_runner.calls)
            self.workbench=restarted;self.client=self.make_client(restarted)
            self.post(self.payload('cancel_editor'));release.set();future.result(timeout=5)
        self.assertFalse(self.snapshot()['draft'])

    def test_stale_base_or_revision_discards_proposal(self):
        payload,_=self.propose()
        def change(job,cancelled):
            with self.workbench.locked() as db:
                state=self.workbench.load(db);state['revision']+=1;self.workbench.save(db,state)
        self.runner.on_run=change;self.workbench.editor.run(payload['operation_id'])
        self.assertEqual('conflict',self.summary()['job']['state']);self.assertFalse(self.snapshot()['draft'])

    def test_changed_model_before_claim_prevents_dispatch(self):
        payload,_=self.propose();self.workbench.editor.execution=dict(EXECUTION,model='other')
        self.workbench.editor.run(payload['operation_id'])
        self.assertEqual([],self.runner.calls);self.assertEqual('conflict',self.summary()['job']['state'])

    def test_failure_is_redacted_and_user_can_start_a_new_request(self):
        payload,_=self.propose()
        def fail(job,cancelled):raise RuntimeError('synthetic-private-provider-body')
        self.runner.on_run=fail;self.workbench.editor.run(payload['operation_id'])
        summary=self.summary();self.assertEqual('failed',summary['job']['state']);self.assertNotIn('private-provider',str(summary))
        self.assertEqual('done',self.propose('New corrected request')[1]['state'])

    def test_unknown_operation_fence_prevents_late_job_creation(self):
        payload=self.payload('propose',goal='Synthetic request')
        fence={k:payload[k] for k in ('protocol_version','operation_id','expected_active','revision')}
        fence.update(action='fence',request_hash=digest(payload),original_action='propose')
        receipt=self.post(fence);self.assertEqual('cancelled',receipt['state']);self.assertEqual(receipt,self.post(payload))
        self.assertIsNone(self.summary()['job'])

    def test_candidate_path_expansion_is_refused_without_changing_active(self):
        payload,_=self.propose();before=self.snapshot()['active']
        original=self.runner.run
        def bad(job,cancelled):
            files=original(job,cancelled);files['../credentials.py']='arbitrary';return files
        self.runner.run=bad;self.workbench.editor.run(payload['operation_id'])
        self.assertEqual('failed',self.summary()['job']['state']);self.assertEqual(before,self.snapshot()['active'])

    def test_manual_change_and_apply_are_blocked_during_generation(self):
        self.propose()
        for action,fields in [('request',{'goal':'x','title':'x','mode':'enqueue_existing'}),('test',{}),('apply',{}),('rollback',{})]:
            self.assertEqual('editor_busy',self.post(self.payload(action,**fields))['code'])

    def test_generated_invalid_policy_can_be_reviewed_but_never_applied(self):
        payload,_=self.propose();original=self.runner.run
        def bad(job,cancelled):
            files=original(job,cancelled);files['mypage_handler.py']='import os\n';return files
        self.runner.run=bad;self.workbench.editor.run(payload['operation_id'])
        self.assertTrue(self.snapshot()['draft']);self.assertEqual('test_failed',self.post(self.payload('test'))['code'])
        self.assertEqual('test_required',self.post(self.payload('apply'))['code'])

    def test_parser_rejects_unbounded_or_secret_capabilities(self):
        for source in [POLICY.replace('adapter.read_state','adapter.read_credentials'),POLICY.replace('return adapter.enqueue_existing(company_id, profile)','return adapter.enqueue_existing("two", profile)'),POLICY.replace('if state == "auth_required":','while state == "auth_required":'),POLICY.replace('state = adapter.read_state(company_id, profile)','state = __import__("os").environ'),POLICY+'\nimport os\n']:
            with self.subTest(source=source),self.assertRaises(ValueError):parse(source)


class HermesAdapterTests(unittest.TestCase):
    def job(self):
        from career_core.mobile_sdui import RUNTIME_ROOT
        files={name:(RUNTIME_ROOT/name).read_text(encoding='utf-8') for name in ('protocol.json','mobile_screen.json','mypage_handler.py')}
        return dict(id='synthetic-editor-0001',company_id='one',profile='default',goal='Change real Bot decision branches',base='wb-'+'a'*48,files=files,source_hash=digest(files),execution=EXECUTION)

    def test_adapter_passes_natural_request_and_pinned_execution_with_no_tools(self):
        job=self.job();calls=[]
        class Agent:
            closed=False
            def run_conversation(self,text,**kwargs):
                calls.append(('request',text,kwargs));files=dict(job['files']);files['mypage_handler.py']=POLICY
                return {'final_response':json.dumps({'files':files})}
            def close(self):self.closed=True
        agent=Agent()
        def factory(identity,prompt,**kwargs):calls.append(('factory',identity,prompt,kwargs));return agent
        runner=HermesEditorRunner(EXECUTION,factory=factory)
        result=runner.run(job,lambda:False)
        self.assertEqual('policy',kind(result['mypage_handler.py']));self.assertTrue(agent.closed)
        self.assertEqual(False,calls[0][3]['tools_enabled']);self.assertEqual([],calls[0][3]['tool_names'])
        self.assertEqual(EXECUTION,calls[0][3]['execution']);self.assertEqual(job['goal'],calls[1][1]);self.assertEqual([],calls[1][2]['conversation_history'])

    def test_changed_execution_and_cancelled_job_never_create_an_agent(self):
        factory=unittest.mock.Mock();runner=HermesEditorRunner(EXECUTION,factory=factory)
        with self.assertRaises(CareerError):runner.run(dict(self.job(),execution=dict(EXECUTION,model='other')),lambda:False)
        with self.assertRaises(CareerError):runner.run(self.job(),lambda:True)
        factory.assert_not_called()

    def test_failure_closes_agent_and_does_not_return_partial_or_extra_output(self):
        job=self.job()
        for result in [{'partial':True,'final_response':'synthetic-private'}, {'final_response':json.dumps({'files':job['files'],'extra':'arbitrary'})}]:
            class Agent:
                closed=False
                def run_conversation(self,*args,**kwargs):return result
                def close(self):self.closed=True
            agent=Agent();runner=HermesEditorRunner(EXECUTION,factory=lambda *args,**kwargs:agent)
            with self.assertRaises(CareerError):runner.run(job,lambda:False)
            self.assertTrue(agent.closed)
