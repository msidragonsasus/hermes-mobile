/* Run from the repository root with Node + Playwright and Chromium installed:
 * NODE_PATH=/path/to/node_modules CHROMIUM_PATH=/usr/bin/chromium node app/src/test/resources/career-browser/fill-dom.test.cjs
 * Synthetic HTTPS responses only. No external portal or credential access.
 * Exercises the actual Kotlin JS templates, not a reimplementation of their guards.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const source = fs.readFileSync('app/src/main/java/com/m57/hermescontrol/career/CareerBrowserFill.kt', 'utf8');
const target = 'https://portal.example.test/company/login?year=2028';
const token = 'dummy-document-token';
const login = 'dummy-user';
const password = 'dummy-password';
const operation = source.match(/val operation =\s*"""([\s\S]*?)"""\.trimIndent\(\)/)[1];
const template = source.match(/return """([\s\S]*?)"""\.trimIndent\(\)/)[1];
function script(fill, options = {}) {
  const values = { target, token, login, password, step: 'combined', allowPasswordOnly: false, legacyAction: null, e2rSiteId: null, selected: null, candidatesOnly: false, ...options };
  if (values.step === 'identifier') values.password = '';
  if (values.step === 'password') { values.login = ''; values.allowPasswordOnly = true; }
  if (values.step === 'password_with_identifier') values.allowPasswordOnly = true;
  const op = fill ? operation.replace(/\$(login|password|expectedStage)\b/g, (_, key) => key === 'expectedStage' ? values.step : JSON.stringify(values[key])) : "return stage === 'password_with_identifier' ? 'ready_password_with_id' : stage === 'identifier' ? 'ready_id' : stage === 'password' ? 'ready_password' : 'ready';";
  return template.replace(/\$(target|token|operation|allowPasswordOnly|legacyAction|e2rSiteId|selected|candidatesOnly)\b/g, (_, key) => key === 'operation' ? op : JSON.stringify(values[key]));
}
const standard = '<form method="post"><input name="username"><input type="password"><button>Login</button></form>';
if (process.argv.includes('--syntax-only')) {
  for (const step of ['combined', 'identifier', 'password', 'password_with_identifier']) {
    new Function(script(true, { step }));
    new Function(script(false, { step }));
  }
  assert.equal(script(true, { step: 'identifier' }).includes(password), false);
  assert.equal(script(true, { step: 'password' }).includes(login), false);
  console.log('PASS: generated JS syntax and step-specific secret exclusion only; DOM behavior not executed');
  process.exit(0);
}
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  let html = standard, header = token;
  const requests = [];
  page.on('request', request => requests.push(request.method()));
  await page.route('**/*', route => route.fulfill({ status: 200, contentType: 'text/html', headers: header ? { 'Server-Timing': `hermes;desc="${header}"` } : {}, body: html }));
  let count = 0;
  async function load(body = standard, documentHeader = token, url = target) {
    html = body; header = documentHeader;
    await page.goto(url);
    await page.evaluate(() => {
      window.events = [];
      for (const name of ['submit', 'input', 'change']) document.addEventListener(name, e => { window.events.push(name); e.preventDefault(); });
    });
  }
  async function check(name, body, expected, options = {}) {
    await load(body, options.header === undefined ? token : options.header, options.url || target);
    const beforeRequests = requests.length;
    const result = await page.evaluate(script(true, options));
    assert.equal(requests.length, beforeRequests, `${name}: fill makes no request`);
    assert.equal(result, expected, name);
    assert.deepEqual(await page.evaluate(() => window.events), [], `${name}: no dispatch or submit`);
    if (!expected.startsWith('filled')) assert.equal(await page.locator('input[type=password]').first().inputValue().catch(() => ''), options.existingPassword || '', `${name}: password untouched`);
    count++;
  }
  try {
    await check('ordinary form', standard, 'filled');
    assert.equal(await page.locator('[name=username]').inputValue(), login);
    assert.equal(await page.locator('[type=password]').inputValue(), password);
    assert.equal(await page.evaluate(script(true)), 'occupied');
    await load(standard);
    assert.equal(await page.evaluate(script(false)), 'ready');
    // Simulate visibility changing after readiness but before an already queued fill executes.
    await page.evaluate(() => Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }));
    const beforeHiddenRequests = requests.length;
    assert.equal(await page.evaluate(script(false)), 'blocked', 'hidden page cannot probe');
    assert.equal(await page.evaluate(script(true)), 'blocked', 'hidden page cannot fill');
    assert.deepEqual(await page.locator('input').evaluateAll(es => es.map(e => e.value)), ['', ''], 'hidden values unchanged');
    assert.deepEqual(await page.evaluate(() => window.events), [], 'hidden page emits no events');
    assert.equal(requests.length, beforeHiddenRequests, 'hidden checks make no requests');
    await page.evaluate(() => { delete document.visibilityState; });
    assert.equal(await page.evaluate(script(false)), 'ready', 'fresh foreground probe succeeds');
    count++;
    await load(standard);
    await page.locator('[name=username]').fill('manual-entry');
    await page.evaluate(() => Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }));
    assert.equal(await page.evaluate(script(true)), 'blocked');
    assert.deepEqual(await page.locator('input').evaluateAll(es => es.map(e => e.value)), ['manual-entry', '']);
    await page.evaluate(() => { delete document.visibilityState; });
    assert.equal(await page.evaluate(script(true)), 'occupied', 'resume preserves manual values');
    count++;
    await check('form action/method/elements clobbering', standard.replace('<button>', '<input type="hidden" name="action"><input type="hidden" name="method"><input type="hidden" name="elements"><button>'), 'filled');
    await check('autocomplete username', standard.replace('name="username"', 'name="candidate-number" autocomplete="section-login username"'), 'filled');
    await check('hidden duplicate credential fields refused', standard.replace('</form>', '<div style="visibility:hidden"><input name="username"><input type="password"></div></form>'), 'ambiguous');
    await check('disabled fieldset template', standard.replace('</form>', '<fieldset disabled><input name="username"><input type="password"></fieldset></form>'), 'filled');
    await check('external associated controls', '<form id="login" method="post"></form><input name="username" form="login"><input type="password" form="login"><button form="login">Login</button>', 'filled');
    await check('normalized login identifier', standard.replace('name="username"', 'name="LOGIN-ID"'), 'filled');
    const idOnly = '<form method="post"><input autocomplete="username"><button>Next</button></form>';
    const passOnly = '<form method="post"><input type="password" autocomplete="current-password"><button>Login</button></form>';
    await load(idOnly);
    assert.equal(await page.evaluate(script(false)), 'ready_id');
    assert.equal(script(true, { step: 'identifier' }).includes(password), false, 'ID step cannot embed password');
    assert.equal(await page.evaluate(script(true)), 'changed', 'combined execution cannot fill ID-only page');
    assert.equal(await page.locator('input').inputValue(), '');
    await check('identifier first step', idOnly, 'filled_id', { step: 'identifier' });
    assert.equal(await page.locator('input').inputValue(), login);
    assert.equal(await page.evaluate(script(false, { allowPasswordOnly: true })), 'pending', 'continuation waits for manual next');
    assert.equal(await page.locator('input').inputValue(), login);
    await load(passOnly);
    assert.equal(await page.evaluate(script(false)), 'pending', 'password-only has no fresh authority');
    assert.equal(await page.evaluate(script(false, { allowPasswordOnly: true })), 'ready_password');
    assert.equal(script(true, { step: 'password' }).includes(login), false, 'password step cannot embed login ID');
    await check('password without continuation', passOnly, 'pending');
    await check('password continuation', passOnly, 'filled_password', { step: 'password' });
    assert.equal(await page.locator('[type=password]').inputValue(), password);
    const visiblePriorId = passOnly.replace('<input ', '<input name="username" value="dummy-user"><input ');
    await load(visiblePriorId);
    assert.equal(await page.evaluate(script(false)), 'occupied', 'fresh visible-ID page has no continuation authority');
    assert.equal(await page.evaluate(script(false, { allowPasswordOnly: true })), 'ready_password_with_id');
    await check('visible identifier password continuation', visiblePriorId, 'filled_password', { step: 'password_with_identifier' });
    assert.equal(await page.locator('[name=username]').inputValue(), login, 'prior ID preserved');
    assert.equal(await page.locator('[type=password]').inputValue(), password);
    await check('visible changed identifier continuation', visiblePriorId.replace('value="dummy-user"', 'value="different-user"'), 'occupied', { step: 'password_with_identifier' });
    assert.equal(await page.locator('[name=username]').inputValue(), 'different-user', 'changed ID preserved');
    assert.equal(await page.locator('[type=password]').inputValue(), '', 'changed account receives no password');
    await check('visible identifier fresh combined fill denied', visiblePriorId, 'occupied');
    await load(visiblePriorId);
    assert.equal(await page.evaluate(script(false, { allowPasswordOnly: true })), 'ready_password_with_id');
    await page.locator('[name=username]').fill('changed-after-probe');
    assert.equal(await page.evaluate(script(true, { step: 'password_with_identifier' })), 'occupied');
    assert.equal(await page.locator('[name=username]').inputValue(), 'changed-after-probe');
    assert.equal(await page.locator('[type=password]').inputValue(), '');
    count++;
    for (const lock of ['readonly', 'disabled']) {
      const lockedPriorId = visiblePriorId.replace('name="username"', `name="username" ${lock}`);
      await check(`${lock} identifier matching continuation`, lockedPriorId, 'filled_password', { step: 'password_with_identifier' });
      assert.equal(await page.locator('[name=username]').inputValue(), login);
      assert.equal(await page.locator('[type=password]').inputValue(), password);
      await check(`${lock} identifier mismatch continuation`, lockedPriorId.replace('value="dummy-user"', 'value="different-user"'), 'occupied', { step: 'password_with_identifier' });
      assert.equal(await page.locator('[name=username]').inputValue(), 'different-user');
      assert.equal(await page.locator('[type=password]').inputValue(), '');
      await check(`${lock} fresh identifier never written`, lockedPriorId, 'pending');
      assert.equal(await page.locator('[name=username]').inputValue(), login);
      assert.equal(await page.locator('[type=password]').inputValue(), '');
      await check(`${lock} blank identifier never written`, lockedPriorId.replace('value="dummy-user"', 'value=""'), 'unsupported', { step: 'password_with_identifier' });
      assert.equal(await page.locator('[name=username]').inputValue(), '');
    }
    await check('password continuation hidden original ID', passOnly.replace('<input ', '<input name="username" type="hidden" value="dummy-user"><input '), 'filled_password', { step: 'password' });
    await check('password continuation different identifier form', passOnly + '<form method="post"><input name="username"></form>', 'ambiguous', { step: 'password' });
    await check('password continuation different URL', passOnly, 'blocked', { step: 'password', url: target.replace('login?', 'password?') });
    await check('password continuation new token', passOnly, 'blocked', { step: 'password', header: 'another-document' });
    await check('identifier foreign action', idOnly.replace('method="post"', 'method="post" action="https://evil.example.test/"'), 'unsafe_action', { step: 'identifier' });
    await check('identifier GET form', idOnly.replace('method="post"', 'method="get"'), 'unsafe_action', { step: 'identifier' });
    await check('identifier multiple candidates', idOnly.replace('</form>', '<input name="email"></form>'), 'ambiguous', { step: 'identifier' });
    await check('password-only new password', passOnly.replace('current-password', 'new-password'), 'unsupported', { step: 'password' });
    await check('two passwords', standard.replace('</form>', '<input type="password"></form>'), 'ambiguous');
    await check('two identifiers', standard.replace('</form>', '<input name="email"></form>'), 'ambiguous');
    await check('GET form', standard.replace('method="post"', 'method="get"'), 'unsafe_action');
    await check('foreign action', standard.replace('method="post"', 'method="post" action="https://evil.example.test/"'), 'unsafe_action');
    await check('different tenant action', standard.replace('method="post"', 'method="post" action="/other/login"'), 'unsafe_action');
    await check('submit override outside form', standard.replace('<form ', '<form id="login" ') + '<button form="login" formaction="https://evil.example.test/">Other</button>', 'unsafe_action');
    await check('GET override', standard.replace('<button>', '<button formmethod="get">'), 'unsafe_action');
    await check('occupied identifier', standard.replace('name="username"', 'name="username" value="already-entered"'), 'occupied');
    await check('occupied password', standard.replace('type="password"', 'type="password" value="existing"'), 'occupied', { existingPassword: 'existing' });
    await check('password creation form', standard.replace('type="password"', 'type="password" autocomplete="new-password"'), 'unsupported');
    await check('missing document token', standard, 'blocked', { header: '' });
    await check('different document token', standard, 'blocked', { header: 'replacement-document' });
    await check('different query', standard, 'blocked', { url: target.replace('2028', '2027') });
    await check('different origin', standard, 'blocked', { url: target.replace('portal.', 'other.') });
    await check('newline credential', standard, 'unsupported', { password: 'dummy\npassword' });
    await check('JSON and JS special characters', standard, 'filled', { login: 'dummy"\\\u2028', password: "dummy'\\\u2029" });
    await load('<main></main>');
    assert.equal(await page.evaluate(script(false)), 'pending');
    assert.equal(script(false).includes(password), false, 'readiness must not contain credentials');
    await page.evaluate(markup => { document.querySelector('main').innerHTML = markup; }, standard);
    assert.equal(await page.evaluate(script(false)), 'ready');
    assert.equal(await page.locator('[type=password]').inputValue(), '');
    assert.equal(await page.evaluate(script(true)), 'filled');
    count++;
    await load(standard.replace('<input name="username">', ''));
    assert.equal(await page.evaluate(script(false)), 'pending');
    await page.evaluate(() => { const input = document.createElement('input'); input.name = 'username'; document.querySelector('form').prepend(input); });
    assert.equal(await page.evaluate(script(false)), 'ready');
    count++;
    await load(standard);
    assert.equal(await page.evaluate(script(false)), 'ready');
    await page.evaluate(() => history.replaceState({}, '', '?year=2027'));
    assert.equal(await page.evaluate(script(true)), 'blocked', 'SPA navigation between probe and fill');
    count++;
    await load('<iframe src="' + target + '"></iframe>');
    assert.equal(await page.frames()[1].evaluate(script(true)), 'blocked', 'same-origin frame blocked');
    count++;
    // Structural fixture from the public PwC i-web form: no branded HTML or real credentials.
    // Public GET 2026-10-05: https://mypage.3030.i-webs.jp/pwc_newgrads28/
    // HTML SHA-256: bc13882cb907be62b0c876430cc9fd9add5a43a9525cc223fe7ab9afa52d2fb0
    // Official link: https://www.pwc.com/jp/ja/careers/consulting/campus.html
    // -> /pwc_newgrads28/applicant/login/baitai-entry/entrycd/C00
    const iwebUrl = 'https://mypage.3030.i-webs.jp/pwc_newgrads28/';
    const iwebAction = iwebUrl + 'applicant/login/check';
    const iwebOptions = { target: iwebUrl, url: iwebUrl, legacyAction: iwebAction };
    const iweb = '<form id="formLogin" name="formLogin" method="post" action="/pwc_newgrads28/applicant/login/check">' +
      '<input type="hidden" name="entrycd" value=""><label for="gksid">ID</label><input type="text" name="gksid" id="gksid" autocomplete="off">' +
      '<input type="password" name="gkspw" id="!gkspw" autocomplete="off">' +
      '<input type="button" value="Login" onclick="window.clicked = true"></form>';
    await load(iweb, token, iwebUrl);
    assert.equal(await page.evaluate(script(false, iwebOptions)), 'ready', 'public-form structure ready before credentials exist');
    assert.equal(script(false, iwebOptions).includes(password), false);
    assert.deepEqual(await page.evaluate(() => window.events), [], 'readiness emits no events');
    assert.deepEqual(await page.locator('input:not([type=hidden]):not([type=button])').evaluateAll(es => es.map(e => e.value)), ['', '']);
    await check('reviewed i-web combined', iweb, 'filled', iwebOptions);
    assert.equal(await page.locator('[name=gksid]').inputValue(), login);
    assert.equal(await page.locator('[name=gkspw]').inputValue(), password);
    assert.equal(await page.evaluate(() => window.clicked), undefined);
    const entryUrl = iwebUrl + 'applicant/login/baitai-entry/entrycd/C00';
    await check('existing reviewed i-web official entry retained', iweb, 'filled', { ...iwebOptions, target: entryUrl, url: entryUrl });
    for (const [name, markup] of [
      ['different form id', iweb.replace('id="formLogin"', 'id="another"')],
      ['different form name', iweb.replace('name="formLogin"', 'name="another"')],
      ['different identifier id with linked label', iweb.replaceAll('"gksid"', '"another"')],
      ['different password id', iweb.replace('id="!gkspw"', 'id="another"')],
      ['different password name', iweb.replace('name="gkspw"', 'name="another"')],
      ['empty unrelated form', iweb + '<form name="formLogin"></form>'],
    ]) await check('generic i-web ' + name, markup, 'filled', iwebOptions);
    const rejectedIweb = [
      ['cross tenant action', iweb.replace('action="/pwc_newgrads28/', 'action="/other/')],
      ['cross origin action', iweb.replace('action="/pwc_newgrads28/', 'action="https://evil.example.test/pwc_newgrads28/')],
      ['wrong endpoint', iweb.replace('/login/check', '/login/other')],
      ['GET form', iweb.replace('method="post"', 'method="get"')],
      ['ambiguous identifiers', iweb.replace('</form>', '<input name="username"></form>')],
      ['hidden duplicate identifier', iweb.replace('</form>', '<input type="hidden" name="gksid"></form>')],
      ['foreign identifier form', iweb + '<form><input name="username"></form>'],
      ['malformed action', iweb.replace('/login/check', '/login/%zz')],
      ['action query', iweb.replace('/login/check', '/login/check?tenant=other')],
      ['override action', iweb.replace('</form>', '<button formaction="/other/login">Other</button></form>')],
      ['override method', iweb.replace('</form>', '<button formmethod="get">Other</button></form>')],
      ['external associated identifier', iweb.replace('<input type="text"', '</form><input form="formLogin" type="text"')],
    ];
    for (const [name, markup] of rejectedIweb) {
      const reason = ['ambiguous identifiers','hidden duplicate identifier','foreign identifier form'].includes(name) ? 'ambiguous' : name === 'external associated identifier' ? 'unsupported' : 'unsafe_action';
      await check('i-web ' + name, markup, reason, iwebOptions);
      assert.equal(await page.evaluate(script(false, iwebOptions)), reason, name + ': readiness refuses before fetch');
    }
    await load(iweb, token, iwebUrl);
    assert.equal(await page.evaluate(script(false, iwebOptions)), 'ready');
    await page.evaluate(() => document.querySelector('form').action = '/other/login');
    assert.equal(await page.evaluate(script(true, iwebOptions)), 'unsafe_action', 'action rechecked after readiness');
    assert.equal(await page.locator('[name=gkspw]').inputValue(), '');
    await check('i-web occupied ID', iweb.replace('id="gksid"', 'id="gksid" value="existing"'), 'occupied', iwebOptions);
    await check('i-web occupied password', iweb.replace('id="!gkspw"', 'id="!gkspw" value="existing"'), 'occupied', { ...iwebOptions, existingPassword: 'existing' });
    await check('i-web changed document', iweb, 'blocked', { ...iwebOptions, url: iwebUrl + '?changed=1' });
    await check('i-web changed tenant', iweb, 'blocked', { ...iwebOptions, url: iwebUrl.replace('pwc_newgrads28', 'other') });
    await check('i-web changed nonce', iweb, 'blocked', { ...iwebOptions, header: 'different' });
    await check('unregistered tenant action stays blocked', iweb, 'unsafe_action');
    // Minimal structure observed on the public Baycurrent E2R page and physical WebView, 2026-10-15.
    const e2rUrl = 'https://www.e2r.jp/ja/baycurrent2028/';
    const e2rOptions = { target: e2rUrl, url: e2rUrl, e2rSiteId: 'baycurrent2028' };
    const e2r = '<form name="frmLogon" method="POST" action="/eARTH/common/Logon">' +
      '<input name="userid"><input type="password" name="password"><button type="submit">Login</button>' +
      '<input type="hidden" name="siteID" value="baycurrent2028">' +
      '<input type="hidden" name="java_script" value="true"><input type="hidden" name="java_enabled" value="true"></form>';
    await load(e2r, token, e2rUrl);
    assert.equal(await page.evaluate(script(false, e2rOptions)), 'ready', 'E2R common login endpoint ready');
    await check('E2R shared endpoint with matching tenant', e2r, 'filled', e2rOptions);
    assert.equal(await page.locator('[name=userid]').inputValue(), login);
    assert.equal(await page.locator('[name=password]').inputValue(), password);
    await check('E2R another registered tenant', e2r.replaceAll('baycurrent2028', 'synthetic2028'), 'filled', {
      target: e2rUrl.replace('baycurrent2028', 'synthetic2028'),
      url: e2rUrl.replace('baycurrent2028', 'synthetic2028'), e2rSiteId: 'synthetic2028'
    });
    for (const [name, markup] of [
      ['different tenant', e2r.replace('value="baycurrent2028"', 'value="another2028"')],
      ['missing tenant', e2r.replace('<input type="hidden" name="siteID" value="baycurrent2028">', '')],
      ['duplicate tenant', e2r.replace('</form>', '<input type="hidden" name="siteID" value="another2028"></form>')],
      ['disabled tenant', e2r.replace('name="siteID"', 'disabled name="siteID"')],
      ['editable tenant', e2r.replace('type="hidden" name="siteID"', 'type="text" name="siteID"')],
      ['different form', e2r.replace('name="frmLogon"', 'name="other"')],
      ['duplicate form', e2r + '<form name="frmLogon"></form>'],
      ['different action', e2r.replace('/eARTH/common/Logon', '/eARTH/common/Other')],
      ['foreign action', e2r.replace('/eARTH/common/Logon', 'https://evil.example.test/eARTH/common/Logon')],
      ['GET method', e2r.replace('method="POST"', 'method="GET"')],
      ['foreign target', e2r.replace('method="POST"', 'method="POST" target="_blank"')],
      ['action query', e2r.replace('/eARTH/common/Logon', '/eARTH/common/Logon?siteID=another2028')],
      ['button override', e2r.replace('type="submit"', 'type="submit" formaction="/other/login"')],
    ]) {
      await check('E2R ' + name, markup, 'unsafe_action', e2rOptions);
      assert.equal(await page.evaluate(script(false, e2rOptions)), 'unsafe_action');
    }
    await load(e2r, token, e2rUrl);
    assert.equal(await page.evaluate(script(false, e2rOptions)), 'ready');
    await page.evaluate(() => document.querySelector('[name=siteID]').value = 'another2028');
    assert.equal(await page.evaluate(script(true, e2rOptions)), 'unsafe_action', 'E2R tenant rechecked before fill');
    assert.equal(await page.locator('[name=password]').inputValue(), '');
    await check('E2R changed document tenant', e2r, 'blocked', { ...e2rOptions, url: e2rUrl.replace('baycurrent2028', 'another2028') });
    await check('E2R changed document token', e2r, 'blocked', { ...e2rOptions, header: 'different' });
    await check('E2R action is not enabled for arbitrary portals', e2r, 'unsafe_action');
    const opaque = '<form method="post"><input name="UserAccount" id="UserAccount"><input name="Password" type="password">' +
      '<input type="hidden" name="csrf" value="dummy-secret-token"><button>Login</button></form>';
    await load(opaque);
    assert.equal(await page.evaluate(script(false)), 'ambiguous', 'opaque identifier triggers model fallback');
    const snapshot = JSON.parse(await page.evaluate(script(false, { candidatesOnly: true })));
    assert.equal(snapshot.fields.length, 2, 'hidden controls never leave WebView');
    assert.equal(JSON.stringify(snapshot).includes('dummy-secret-token'), false);
    assert.equal(snapshot.fields.some(e => Object.hasOwn(e, 'value')), false);
    const selected = { loginIndex: 0, passwordIndex: 1, signature: snapshot.signature };
    assert.equal(await page.evaluate(script(false, { selected })), 'ready', 'model indexes pass full production guards');
    assert.equal(await page.evaluate(script(true, { selected })), 'filled');
    assert.deepEqual(await page.evaluate(() => window.events), []);
    await load(opaque);
    await page.evaluate(() => document.querySelector('[name=UserAccount]').value = 'existing');
    assert.equal(await page.evaluate(script(true, { selected })), 'changed', 'typed values while model waits preserved');
    assert.equal(await page.locator('[type=password]').inputValue(), '');
    await load(opaque);
    await page.evaluate(() => document.querySelector('form').action = 'https://evil.example.test/login');
    assert.equal(await page.evaluate(script(true, { selected })), 'unsafe_action', 'model cannot authorize another action');
    await load(opaque);
    await page.evaluate(() => document.querySelector('[name=UserAccount]').name = 'captcha');
    assert.equal(await page.evaluate(script(true, { selected })), 'changed', 'model result is bound to structural snapshot');
    await check('model indexes cannot name another document', opaque, 'blocked', { selected, header: 'different' });
    const injected = opaque.replace('name="UserAccount"', 'name="captcha"');
    await load(injected);
    const captchaSnapshot = JSON.parse(await page.evaluate(script(false, { candidatesOnly: true })));
    assert.equal(await page.evaluate(script(true, { selected: { ...selected, signature: captchaSnapshot.signature } })), 'unsupported');
    assert.equal(await page.locator('[type=password]').inputValue(), '');
    count += 7;
    assert.equal(requests.includes('POST'), false, 'no form submissions across suite');
    console.log(`PASS: ${count} real Chromium DOM cases, native navigation Server-Timing header, no synthetic navigation API`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
