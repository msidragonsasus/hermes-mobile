/** Offline Chromium tests of the JavaScript embedded in production Kotlin. Uses synthetic values only.
 * Run: NODE_PATH=/path/to/node_modules node tools/test-career-autofill.cjs
 * Requirements: playwright and a Chromium binary (CHROMIUM_PATH may override).
 * No Android compilation is performed. No external requests are permitted.
 */
const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
const { chromium } = process.env.AUTOFILL_TEST_DOUBLE === '1' ? require('./synthetic-dom.cjs') : require('playwright');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/m57/hermescontrol/career/CareerBrowserFill.kt'), 'utf8');
const guard = source.match(/return """\n([\s\S]*?)\n\s*"""\.trimIndent\(\)\n\s*}\n}/)[1];
const operation = source.match(/val operation =\s*"""\n([\s\S]*?)\n\s*"""\.trimIndent/)[1];
const USER = 'synthetic-user', PASS = 'synthetic-password', TOKEN = 'synthetic-document-token';
const readiness = "return stage === 'password_with_identifier' ? 'ready_password_with_id' : stage === 'identifier' ? 'ready_id' : stage === 'password' ? 'ready_password' : 'ready';";
function script(url, step, token = TOKEN, continuation = false, legacyAction = null) {
  let op = readiness;
  if (step) {
    op = operation.replaceAll('$expectedStage', step)
      .replaceAll('$login', JSON.stringify(step === 'password' ? '' : USER))
      .replaceAll('$password', JSON.stringify(step === 'identifier' ? '' : PASS));
    continuation = ['password', 'password_with_identifier'].includes(step);
  }
  return guard.replaceAll('$legacyAction', JSON.stringify(legacyAction)).replaceAll('$target', JSON.stringify(url)).replaceAll('$token', JSON.stringify(token))
    .replaceAll('$allowPasswordOnly', String(continuation)).replaceAll('$operation', op);
}
const defaultURL = 'https://portal.example.test/tenant/login';
const form = (inner = '<input name="username"><input name="password" type="password">', action = '/tenant/auth', extra = '') =>
  `<form method="post" action="${action}" ${extra}>${inner}<button type="submit">ログイン</button></form>`;
(async () => {
  const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox']});
  const context = await browser.newContext();
  const page = await context.newPage();
  let html = '', activeURL = '', unexpected = 0, count = 0;
  await context.route('**/*', route => {
    if (route.request().isNavigationRequest() && route.request().url() === activeURL && route.request().method() === 'GET') {
      return route.fulfill({status: 200, contentType: 'text/html', headers: {'Server-Timing': `hermes;desc="${TOKEN}"`}, body: html});
    }
    unexpected++;
    return route.abort();
  });
  async function test(name, markup, expected, options = {}) {
    const url = options.url || defaultURL;
    activeURL = url;
    html = `<!doctype html><meta charset="utf-8"><style>input,button{width:200px;height:30px}</style>${markup}`;
    await page.goto(url);
    if (process.env.AUTOFILL_TEST_DOUBLE !== '1') await page.evaluate(() => {
      window.autofillEvents = [];
      for (const event of ['input','change','submit']) document.addEventListener(event, e => {
        window.autofillEvents.push(event); e.preventDefault();
      });
    });
    const before = unexpected;
    const probe = await page.evaluate(script(url, null, options.token || TOKEN, options.continuation || false, options.legacyAction || null));
    assert.equal(probe, expected, name + ': probe');
    if (options.mutate) await page.evaluate(options.mutate);
    if (options.fill !== false && (expected.startsWith('ready') || options.mutate)) {
      const step = options.step || ({ready: 'combined', ready_id: 'identifier', ready_password: 'password', ready_password_with_id: 'password_with_identifier'})[expected] || 'combined';
      const result = await page.evaluate(script(url, step, options.token || TOKEN, false, options.legacyAction || null));
      const expectedResult = options.result || ({combined:'filled',identifier:'filled_id',password:'filled_password',password_with_identifier:'filled_password'})[step];
      assert.equal(result, expectedResult, name + ': fill');
      assert.ok(!result.includes(USER) && !result.includes(PASS), name + ': no secret result');
      if (expectedResult.startsWith('filled')) {
        const values = await page.locator('input').evaluateAll(es => es.map(e => ({type:e.type,value:e.value,name:e.name})));
        if (step !== 'password') assert.ok(values.some(e => e.value === USER), name + ': identifier filled');
        if (step !== 'identifier') assert.ok(values.some(e => e.type === 'password' && e.value === PASS), name + ': password filled');
      }
    }
    if (process.env.AUTOFILL_TEST_DOUBLE !== '1') {
      assert.deepEqual(await page.evaluate(() => window.autofillEvents), [], name + ': no events/submission');
    }
    assert.equal(unexpected, before, name + ': no external traffic/submission');
    count++;
    console.log('PASS ' + name);
  }
  try {
    await test('generic same-directory POST', form(), 'ready');
    await test('exact target POST', form(undefined, '/tenant/login'), 'ready');
    await test('JR actual dt/dd structure without provider-specific rules', form('<div class="login_txt clearfix"><dl class="login_inputArea"><dt>ID</dt><dd><input name="gksid" id="gksid" autocomplete="off"></dd></dl><dl class="login_inputArea password"><dt>PASSWORD</dt><dd><input type="password" name="gkspw" id="!gkspw" autocomplete="off"></dd></dl></div>', '/jr-central2028/applicant/login/check'), 'ready', {url:'https://mypage.3070.i-webs.jp/jr-central2028/'});
    await test('PwC root uses identical generic logic', form('<label>ID<input name="random_user"></label><input name="random_pass" type="password">', '/pwc_newgrads28/applicant/login/check'), 'ready', {url:'https://mypage.3030.i-webs.jp/pwc_newgrads28/'});
    await test('unknown entry route does not erase path selectors', form(undefined, '/pwc_newgrads28/applicant/login/check'), 'unsafe_action', {url:'https://mypage.3030.i-webs.jp/pwc_newgrads28/applicant/login/baitai-entry/entrycd/C00'});
    await test('unrelated nested portal uses identical generic logic', form(undefined, '/zw/s/randomco/mypage/auth'), 'ready', {url:'https://axol.jp/zw/s/randomco/mypage/login'});
    await test('bounded table row identifier label', form('<table><tr><th>ID</th><td><input name="opaque"></td></tr></table><input type="password">'), 'ready');
    await test('Japanese associated label', form('<label for="random">会員ID</label><input id="random"><input type="password">'), 'ready');
    await test('ARIA label', form('<input aria-label="ユーザーID"><input type="password">'), 'ready');
    await test('ARIA linked label', form('<span id="label">メールアドレス</span><input aria-labelledby="label"><input type="password">'), 'ready');
    await test('autocomplete semantic identifier', form('<input name="opaque" autocomplete="section-login username"><input type="password" autocomplete="current-password">'), 'ready');
    await test('opaque text field never assumed to be account ID', form('<input name="opaque"><input type="password">'), 'ambiguous');
    await test('root auth endpoint', form(undefined, '/login/check'), 'ready', {url:'https://portal.example.test/'});
    await test('query tenant unchanged', form(undefined, '/tenant/auth?tenant=one'), 'ready', {url:defaultURL+'?tenant=one'});
    await test('identifier-only explicit next step', form('<input name="username">'), 'ready_id');
    await test('password-only requires continuation', form('<input type="password">'), 'pending');
    await test('password-only host continuation', form('<input type="password">'), 'ready_password', {continuation:true});
    await test('locked identifier comparison continuation', form(`<input name="username" value="${USER}" readonly><input type="password">`), 'ready_password_with_id', {continuation:true});
    await test('changed identifier continuation occupied', form('<input name="username" value="another" readonly><input type="password">'), 'ready_password_with_id', {continuation:true,result:'occupied'});
    await test('disabled identifier comparison continuation', form(`<input name="username" value="${USER}" disabled><input type="password">`), 'ready_password_with_id', {continuation:true});
    await test('disabled different account prevents password fill', form('<input name="username" value="another" disabled><input type="password">'), 'ready_password_with_id', {continuation:true,result:'occupied'});
    await test('OTP password field rejected', form('<input name="username"><input type="password" autocomplete="one-time-code">'), 'unsupported');
    await test('positive-offset decoy identifier never filled', form('<input name="username" style="position:absolute;left:9999px"><input type="password">'), 'pending');
    await test('below-viewport decoy identifier never filled', form('<input name="username" style="position:absolute;top:9999px"><input type="password">'), 'pending');
    await test('earlier auth segment cannot erase tenant', form(undefined, '/login'), 'unsafe_action', {url:'https://portal.example.test/auth/acme/login'});
    await test('nested auth tenant own endpoint', form(undefined, '/auth/acme/auth'), 'ready', {url:'https://portal.example.test/auth/acme/login'});
    await test('repeated slash registration cannot broaden scope', form(undefined, '/tenant/company/auth'), 'unsafe_action', {url:'https://portal.example.test/tenant//company/login'});
    await test('leading repeated slash cannot broaden scope', form(undefined, '/tenant/auth'), 'unsafe_action', {url:'https://portal.example.test//tenant/login'});
    await test('disabled fieldset template ignored', form()+'<fieldset disabled><input name="template_user"><input type="password"></fieldset>', 'ready');
    await test('confirmation password rejected without autocomplete', form('<input name="username"><input type="password" name="confirmPassword">'), 'unsupported');
    await test('new password Japanese label rejected', form('<input name="username"><label>新しいパスワード<input type="password"></label>'), 'unsupported');
    await test('registration button on exact registered URL rejected', form(undefined, '/tenant/login').replace('ログイン', '新規登録'), 'unsupported');
    await test('reset button on exact registered URL rejected', form(undefined, '/tenant/login').replace('ログイン', 'Reset password'), 'unsupported');
    await test('overflow-clipped identifier never filled', form('<div style="width:1px;overflow:hidden"><input name="username"></div><input type="password">'), 'pending');
    await test('tenant after auth marker cannot collapse to root', form(undefined, '/login'), 'unsafe_action', {url:'https://portal.example.test/auth/acme/'});
    await test('tenant after login marker cannot collapse to root', form(undefined, '/login'), 'unsafe_action', {url:'https://portal.example.test/login/acme/'});
    await test('nested tenant after auth marker cannot collapse', form(undefined, '/platform/login'), 'unsafe_action', {url:'https://portal.example.test/platform/auth/acme/'});
    const legacyURL = 'https://mypage.3030.i-webs.jp/pwc_newgrads28/applicant/login/baitai-entry/entrycd/C00';
    const legacyAction = 'https://mypage.3030.i-webs.jp/pwc_newgrads28/applicant/login/check';
    const legacyMarkup = form('<dl><dt>ID</dt><dd><input name="gksid" id="gksid"></dd></dl><input name="gkspw" id="!gkspw" type="password">', legacyAction, 'id="formLogin" name="formLogin"');
    await test('existing reviewed PwC entry compatibility retained', legacyMarkup, 'ready', {url:legacyURL,legacyAction});
    await test('legacy exception still requires exact known form identity', legacyMarkup.replace('id="formLogin"','id="other"'), 'unsupported', {url:legacyURL,legacyAction});
    await test('legacy exception still rejects foreign action', legacyMarkup.replace(legacyAction, 'https://evil.example.test/login'), 'unsafe_action', {url:legacyURL,legacyAction});
    const rejected = [
      ['cross-origin action','https://evil.example.test/tenant/login','unsafe_action'],
      ['different tenant','/other/login','unsafe_action'],
      ['tenant prefix collision','/tenantEvil/login','unsafe_action'],
      ['HTTP action','http://portal.example.test/tenant/login','unsafe_action'],
      ['nonstandard port','https://portal.example.test:444/tenant/login','unsafe_action'],
      ['userinfo action','https://u:p@portal.example.test/tenant/login','unsafe_action'],
      ['encoded path separator','/tenant/%2flogin','unsafe_action'],
      ['encoded dot traversal','/tenant/%2e%2e/tenant/login','unsafe_action'],
      ['literal traversal','/tenant/../tenant/login','unsafe_action'],
      ['path parameter','/tenant/login;other','unsafe_action'],
      ['non-auth action','/tenant/profile/save','unsafe_action'],
      ['query injected','/tenant/auth?tenant=other','unsafe_action']
    ];
    for (const [name,action,result] of rejected) await test(name, form(undefined,action),result);
    await test('nested sibling tenant', form(undefined,'/zw/s/other/mypage/login'), 'unsafe_action', {url:'https://axol.jp/zw/s/randomco/mypage/login'});
    await test('no trailing slash cannot broaden tenant scope', form(undefined,'/zw/s/other/login'), 'unsafe_action', {url:'https://axol.jp/zw/s/randomco'});
    await test('root cannot authorize child tenant', form(undefined,'/other/login'), 'unsafe_action', {url:'https://portal.example.test/'});
    await test('query tenant dropped', form(), 'unsafe_action', {url:defaultURL+'?tenant=one'});
    await test('query tenant changed', form(undefined,'/tenant/auth?tenant=two'), 'unsafe_action', {url:defaultURL+'?tenant=one'});
    await test('GET method', form().replace('method="post"','method="get"'), 'unsafe_action');
    await test('foreign target frame', form(undefined,undefined,'target="other"'), 'unsafe_action');
    await test('submitter foreign action', form('<input name="username"><input type="password"><button formaction="https://evil.example.test/login">login</button>'), 'unsafe_action');
    await test('external form-associated override', form(undefined,undefined,'id="f"')+'<button form="f" formmethod="get">login</button>', 'unsafe_action');
    await test('submitter target override', form('<input name="username"><input type="password"><button formtarget="_blank">login</button>'), 'unsafe_action');
    await test('multiple visible password forms', form()+form(), 'ambiguous');
    await test('two identifier candidates', form('<input name="username"><input name="email"><input type="password">'), 'ambiguous');
    await test('unknown extra text candidate', form('<input name="username"><input name="opaque"><input type="password">'), 'ambiguous');
    await test('hidden duplicate credential name', form('<input name="username"><input type="hidden" name="username"><input type="password">'), 'ambiguous');
    await test('duplicate selected id outside form', form('<input name="username" id="chosen"><input type="password">')+'<div id="chosen"></div>', 'ambiguous');
    await test('hidden textarea credential name', form('<input name="username"><textarea name="username" hidden></textarea><input type="password">'), 'ambiguous');
    await test('hidden template ignored', form()+'<div hidden><input name="template_user"><input type="password" name="template_password"></div>', 'ready');
    await test('opacity zero identifier never filled', form('<input name="username" style="opacity:0"><input type="password">'), 'pending');
    await test('offscreen decoy identifier never filled', form('<input name="username" style="position:absolute;left:-9999px"><input type="password">'), 'pending');
    await test('OTP is not identifier fallback', form('<input name="otp" autocomplete="one-time-code"><input type="password">'), 'ambiguous');
    await test('company id is not account identifier', form('<input name="companyId"><input type="password">'), 'ambiguous');
    await test('new password registration rejected', form('<input name="username"><input type="password" autocomplete="new-password">'), 'unsupported');
    await test('occupied values retained', form('<input name="username" value="manual"><input type="password">'), 'occupied');
    await test('formless fields rejected', '<input name="username"><input type="password">', 'unsupported');
    await test('identifier elsewhere is ambiguous', form('<input type="password">')+'<form><input name="username"></form>', 'ambiguous');
    await test('native document token mismatch', form(), 'blocked', {token:'stale-token'});
    await test('action changes between probe and fill', form(), 'ready', {mutate:()=>document.querySelector('form').action='https://evil.example.test/login', result:'unsafe_action'});
    await test('URL changes between probe and fill', form(), 'ready', {mutate:()=>history.pushState({},'', '/other/login'), result:'blocked'});
    await test('field ownership changes between probe and fill', form()+'<form id="other"></form>', 'ready', {mutate:()=>document.querySelector('input').setAttribute('form','other'), result:'ambiguous'});
    await test('password becomes hidden between probe and fill', form(), 'ready', {mutate:()=>document.querySelector('[type=password]').style.display='none', result:'changed'});
    await test('manual entry before fill retained', form(), 'ready', {mutate:()=>document.querySelector('input').value='manual', result:'occupied'});
    await test('event-free operation (live counters require Chromium mode)', form()+`<script>document.addEventListener('input',()=>{throw Error('must not dispatch input')});document.addEventListener('change',()=>{throw Error('must not dispatch change')});document.querySelector('form').onsubmit=()=>{throw Error('must not submit')};</script>`, 'ready');
    assert.ok(!script(defaultURL).includes(USER) && !script(defaultURL).includes(PASS));
    assert.ok(!script(defaultURL,'identifier').includes(PASS));
    assert.ok(!script(defaultURL,'password').includes(USER));
    assert.ok(!/\.dispatchEvent\(|\.submit\(|\.requestSubmit\(|\.click\(/.test(script(defaultURL,'combined')));
    console.log(JSON.stringify({passed:count,failed:0,externalRequests:unexpected,engine:await browser.version(),validation:process.env.AUTOFILL_TEST_DOUBLE === '1' ? 'embedded production JavaScript with explicit DOM/layout test doubles; NOT a real browser or Android build' : 'embedded production JavaScript, real Chromium with synthetic pages; no Android build'},null,2));
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
