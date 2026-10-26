package com.m57.hermescontrol.career

import kotlinx.serialization.json.JsonPrimitive

internal enum class CareerBrowserFillStep {
    COMBINED,
    IDENTIFIER,
    PASSWORD,
    PASSWORD_WITH_IDENTIFIER,
}

internal object CareerBrowserFill {
    /** The host grants password-only probing only after its own identifier-step success. */
    fun readinessScript(
        policy: CareerBrowserPolicy,
        documentToken: String,
        allowPasswordOnly: Boolean = false,
        selection: CareerBrowserLoginSelection? = null,
    ): String =
        guardedScript(
            policy,
            documentToken,
            allowPasswordOnly,
            "return stage === 'password_with_identifier' ? 'ready_password_with_id' : " +
                "stage === 'identifier' ? 'ready_id' : stage === 'password' ? 'ready_password' : 'ready';",
            selection,
        )

    fun candidateScript(
        policy: CareerBrowserPolicy,
        documentToken: String,
    ): String = guardedScript(policy, documentToken, false, "return 'unsupported';", candidatesOnly = true)

    /** Serialize only the selected step's values; every execution repeats the complete guards. */
    fun script(
        policy: CareerBrowserPolicy,
        credentials: CareerBrowserCredentials,
        documentToken: String,
        step: CareerBrowserFillStep = CareerBrowserFillStep.COMBINED,
        selection: CareerBrowserLoginSelection? = null,
    ): String {
        val expectedStage = step.name.lowercase()
        val login =
            JsonPrimitive(
                if (step == CareerBrowserFillStep.PASSWORD) "" else credentials.loginId.concatToString(),
            ).toString()
        val password =
            JsonPrimitive(
                if (step == CareerBrowserFillStep.IDENTIFIER) "" else credentials.password.concatToString(),
            ).toString()
        val operation =
            """
            // Events can auto-submit or copy secrets in page handlers. Keep this operation input-only.
            // No input/change event, submit(), click(), observer, bridge or secret-valued result.
            if (stage !== '$expectedStage') return 'changed';
            const loginValue = $login, passwordValue = $password;
            if (/[\r\n]/.test(loginValue + passwordValue)) return 'unsupported';
            if (stage === 'password_with_identifier' && ids[0].value !== loginValue) return 'occupied';
            if (!currentDocument() || !safeForm() || fields.some(e => !e.isConnected || e.form !== form))
              return 'changed';
            const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            if (stage === 'combined' || stage === 'identifier') setValue.call(ids[0], loginValue);
            if (stage !== 'identifier') setValue.call(pass, passwordValue);
            if ((ids.length && ids[0].value !== loginValue) || (pass && pass.value !== passwordValue))
              return 'not_retained';
            return stage === 'identifier' ? 'filled_id' : stage === 'combined' ? 'filled' : 'filled_password';
            """.trimIndent()
        return guardedScript(
            policy,
            documentToken,
            step == CareerBrowserFillStep.PASSWORD || step == CareerBrowserFillStep.PASSWORD_WITH_IDENTIFIER,
            operation,
            selection,
        )
    }

    private fun guardedScript(
        policy: CareerBrowserPolicy,
        documentToken: String,
        allowPasswordOnly: Boolean,
        operation: String,
        selection: CareerBrowserLoginSelection? = null,
        candidatesOnly: Boolean = false,
    ): String {
        val target = JsonPrimitive(policy.loginUrl.toString()).toString()
        val token = JsonPrimitive(documentToken).toString()
        val legacyAction = policy.reviewedFormAction?.let { JsonPrimitive(it).toString() } ?: "null"
        val e2rSiteId = policy.reviewedE2rSiteId?.let { JsonPrimitive(it).toString() } ?: "null"
        val fillPrefix = policy.fillPathPrefix?.let { JsonPrimitive(it).toString() } ?: "null"
        val selected = selection?.json()?.toString() ?: "null"
        return """
            (() => {
              try {
                const expected = $target;
                const selected = $selected;
                const legacyAction = $legacyAction;
                const registered = new URL(expected);
                const e2rSiteId = $e2rSiteId;
                const e2rAction = e2rSiteId === null ? null : registered.origin + '/eARTH/common/Logon';
                const fillPrefix = $fillPrefix;
                // Mirrors CareerBrowserPolicy.allowsFill: exact page, or same host under the company
                // directory with every registered query parameter unchanged.
                const inScope = u => {
                  if (u.origin + u.pathname + u.search === expected) return true;
                  if (fillPrefix === null || u.origin !== registered.origin || u.username || u.password ||
                      /;|%(?:2f|5c|25|3b)/i.test(u.pathname) || u.pathname.includes('//') ||
                      !(u.pathname + '/').startsWith(fillPrefix)) return false;
                  return [...new Set(registered.searchParams.keys())].every(k =>
                    JSON.stringify(u.searchParams.getAll(k)) === JSON.stringify(registered.searchParams.getAll(k)));
                };
                const currentDocument = () => {
                  if (document.visibilityState === 'hidden' || window !== window.top ||
                      !inScope(new URL(location.href))) return false;
                  const navigation = performance.getEntriesByType('navigation')[0];
                  return !!navigation && navigation.serverTiming.some(t =>
                    t.name === 'hermes' && t.description === $token);
                };
                if (!currentDocument()) return 'blocked';
                const clean = value => { const u = new URL(value, location.href); u.hash = ''; return u.href; };
                const authPart = /^(?:login|logon|sign-?in|auth|authenticate|authentication|sessions?)(?:\.(?:php|aspx?|jsp|html?))?$/i;
                const segments = registered.pathname.split('/').filter(Boolean);
                const authIndex = segments.map(p => authPart.test(p)).lastIndexOf(true);
                const authTail = authIndex < 0 ? [] : segments.slice(authIndex + 1);
                const knownAuthTail = authTail.length === 0 || authTail.length === 1 &&
                  /^(?:check|verify|submit|authenticate|create)$/i.test(authTail[0]);
                // Retain every tenant segment (including nested platform paths), never only the first.
                // An unknown final segment is a tenant, not an assumed filename.
                const prefixParts = authIndex >= 0 ? segments.slice(0, authIndex) :
                  segments.filter((p, i) => !(i === segments.length - 1 && /^index\.(?:php|aspx?|jsp|html?)$/i.test(p)));
                const prefix = '/' + (prefixParts.length ? prefixParts.join('/') + '/' : '');
                const authSuffix = /^(?:(?:applicant|account|member|user)\/)?(?:login|logon|sign-?in|auth|authenticate|authentication|sessions?)(?:\.(?:php|aspx?|jsp|html?))?(?:\/(?:check|verify|submit|authenticate|create))?\/?$/i;
                const allowedAction = raw => {
                  if (/[\\\s\u0000-\u001f]/.test(raw)) return false;
                  const rawPath = raw.split(/[?#]/)[0];
                  if (/[%;]/.test(rawPath) || /(?:^|\/)\.{1,2}(?:\/|$)/.test(rawPath)) return false;
                  const u = new URL(raw || location.href, document.baseURI);
                  // No encoded path tricks, path parameters, foreign ports/origins, or query tenant swaps.
                  if (u.origin !== registered.origin || u.username || u.password ||
                      /[%;\\]/.test(u.pathname)) return false;
                  if (clean(u.href) === expected) return true;
                  if (legacyAction !== null && clean(u.href) === legacyAction) return true;
                  if (e2rAction !== null && clean(u.href) === e2rAction) return true;
                  // A form posting back inside the company's login scope is as trusted as the page itself.
                  if (inScope(u)) return true;
                  if (u.search !== registered.search) return false;
                  if (!knownAuthTail || registered.pathname.includes('//') || u.pathname.includes('//')) return false;
                  if (!u.pathname.startsWith(prefix)) return false;
                  return authSuffix.test(u.pathname.slice(prefix.length));
                };
                const rendered = e => {
                  if (e.type === 'hidden' || e.closest('[inert],[hidden],[aria-hidden="true"]') ||
                      !e.getClientRects().length) return false;
                  for (let p = e; p && p.nodeType === 1; p = p.parentElement) {
                    const style = getComputedStyle(p);
                    if (style.display === 'none' || style.visibility === 'hidden' ||
                        style.visibility === 'collapse' || Number(style.opacity) === 0) return false;
                  }
                  const r = e.getBoundingClientRect();
                  let left = Math.max(0, r.left), top = Math.max(0, r.top);
                  let right = Math.min(window.innerWidth, r.right), bottom = Math.min(window.innerHeight, r.bottom);
                  for (let p = e.parentElement; p; p = p.parentElement) {
                    const style = getComputedStyle(p), clip = p.getBoundingClientRect();
                    if (/(hidden|clip|auto|scroll)/.test(style.overflowX)) {
                      left = Math.max(left, clip.left); right = Math.min(right, clip.right);
                    }
                    if (/(hidden|clip|auto|scroll)/.test(style.overflowY)) {
                      top = Math.max(top, clip.top); bottom = Math.min(bottom, clip.bottom);
                    }
                  }
                  return right - left >= 2 && bottom - top >= 2;
                };
                const editable = e => rendered(e) && !e.matches(':disabled') && !e.readOnly;
                const autocomplete = e => (e.autocomplete || '').trim().toLowerCase().split(/\s+/).pop();
                const normalize = text => (text || '').normalize('NFKC').toLowerCase().replace(/[_\-\s:：*＊]/g, '');
                const labelText = e => {
                  const linked = (e.getAttribute('aria-labelledby') || '').split(/\s+/)
                    .map(id => document.getElementById(id)).filter(Boolean);
                  const labels = [...(e.labels || []), ...linked].map(n => n.textContent || '');
                  labels.push(e.getAttribute('aria-label') || '', e.getAttribute('placeholder') || '');
                  // Old table/list forms may lack label-for. Only a bounded single-input row is evidence.
                  const row = e.closest('tr,li,dd,.form-group,.form-item');
                  if (row && row.querySelectorAll('input:not([type="hidden"]),textarea,select').length === 1 &&
                      row.textContent.length <= 160) labels.push(row.textContent);
                  const definition = e.closest('dd');
                  const term = definition && definition.previousElementSibling;
                  if (term && term.tagName === 'DT' && definition.parentElement.tagName === 'DL' &&
                      definition.querySelectorAll('input:not([type="hidden"]),textarea,select').length === 1 &&
                      term.textContent.length <= 80 && rendered(term)) labels.push(term.textContent);
                  if (e.parentElement && e.parentElement.querySelectorAll('input,textarea,select').length === 1 &&
                      e.parentElement.textContent.length <= 80) labels.push(e.parentElement.textContent);
                  return labels.join('\n');
                };
                const identityNames = new Set(['id','login','loginid','username','userid','user','email','mail',
                  'emailaddress','mailaddress','account','accountid','memberid','applicantid']);
                const negative = /password|passwd|パスワード|verification|verifycode|captcha|one.?time|otp|認証コード|確認コード|検索|search|companyid|tenant|organization|企業id|会社id/;
                const identifierLabel = /^(?:id|email|e-mail|メール(?:アドレス)?|会員(?:id|番号)|ユーザー(?:id|名)|ログイン(?:id|名)|アカウント(?:id|名)|応募者(?:id|番号))(?:[（(].*|を入力.*)?$/;
                const textField = e => e.tagName === 'INPUT' && ['text','email','tel'].includes(e.type) &&
                  ($allowPasswordOnly ? rendered(e) : editable(e));
                const isIdentifier = e => {
                  if (!textField(e) || ['one-time-code','new-password','current-password'].includes(autocomplete(e))) return false;
                  const label = normalize(labelText(e));
                  const metadata = normalize(e.name) + ' ' + normalize(e.id) + ' ' + label;
                  if (negative.test(metadata)) return false;
                  return selected !== null && inputs[selected.loginIndex] === e ||
                    autocomplete(e) === 'username' || identityNames.has(normalize(e.name)) ||
                    identityNames.has(normalize(e.id)) || labelText(e).split('\n').some(t => identifierLabel.test(normalize(t))) ||
                    /(?:loginid|userid|username|accountid|emailaddress|メールアドレス|ログインid|ユーザーid|会員id)/.test(label);
                };
                const inputs = [...document.querySelectorAll('input')];
                const safeName = value => /^[A-Za-z0-9_.:-]{1,80}$/.test(value || '') ? value : '';
                const snapshot = () => inputs.map((e, index) => ({e, index}))
                  .filter(({e}) => ['text','email','tel','password'].includes(e.type) && editable(e) && e.form)
                  .map(({e,index}) => ({index, form:[...document.forms].indexOf(e.form), type:e.type,
                    name:safeName(e.name), id:safeName(e.id),
                    autocomplete:['username','current-password','new-password','one-time-code'].includes(autocomplete(e)) ? autocomplete(e) : '',
                    labelHints:['ログイン','ユーザー','アカウント','メール','パスワード','認証コード','確認コード','検索','login','account','password','email','captcha']
                      .filter(word => normalize(labelText(e)).includes(word)), occupied:!!e.value}));
                if ($candidatesOnly) {
                  const fields = snapshot();
                  if (fields.length < 1 || fields.length > 64) return 'unsupported';
                  return JSON.stringify({fields, signature:JSON.stringify(fields)});
                }
                if (selected !== null && JSON.stringify(snapshot()) !== selected.signature) return 'changed';
                const passwords = inputs.filter(e => e.type === 'password' && rendered(e) && !e.matches(':disabled') &&
                  (selected === null || inputs[selected.passwordIndex] === e));
                if (passwords.length > 1) return 'ambiguous';
                const pass = passwords[0] || null;
                if (pass && (!editable(pass) || ['new-password','one-time-code'].includes(autocomplete(pass)) ||
                    /otp|one.?time|captcha|認証コード|確認コード|newpassword|confirmpassword|repeatpassword|resetpassword|passwordconfirmation|新.*パスワード|パスワード.*(?:確認|再入力|再設定|変更)/.test(
                      normalize(pass.name + ' ' + pass.id + ' ' + labelText(pass)))))
                  return 'unsupported';
                const candidates = inputs.filter(e => isIdentifier(e) && (selected === null || inputs[selected.loginIndex] === e));
                if (selected !== null && candidates.length !== 1) return 'unsupported';
                if (!pass && !candidates.length) return 'pending';
                if (!pass && candidates.length !== 1) return 'ambiguous';
                const form = pass ? pass.form : candidates[0].form;
                if (!form) return 'unsupported';
                // Named controls can shadow form.action, form.method and form.elements.
                const formProperty = name => Object.getOwnPropertyDescriptor(HTMLFormElement.prototype, name).get.call(form);
                const safeForm = () => {
                  if (!form.isConnected || formProperty('method').toLowerCase() !== 'post' ||
                      !['', '_self'].includes(formProperty('target').toLowerCase()) ||
                      !allowedAction(form.getAttribute('action') || '')) return false;
                  const action = clean(formProperty('action'));
                  if (e2rAction !== null && action === e2rAction) {
                    const tenants = [...formProperty('elements')].filter(e => e.name === 'siteID');
                    if (form.getAttribute('name') !== 'frmLogon' ||
                        document.querySelectorAll('form[name="frmLogon"]').length !== 1 ||
                        tenants.length !== 1 || tenants[0].tagName !== 'INPUT' || tenants[0].type !== 'hidden' ||
                        tenants[0].matches(':disabled') || tenants[0].parentElement.closest('form') !== form ||
                        tenants[0].value !== e2rSiteId) return false;
                  }
                  return [...document.querySelectorAll('[formaction],[formmethod],[formtarget]')].every(e =>
                    e.form !== form || (
                      (!e.hasAttribute('formaction') || allowedAction(e.getAttribute('formaction')) && clean(e.formAction) === action) &&
                      (!e.hasAttribute('formmethod') || e.formMethod.toLowerCase() === 'post') &&
                      (!e.hasAttribute('formtarget') || ['', '_self'].includes(e.formTarget.toLowerCase()))));
                };
                if (!safeForm()) return 'unsafe_action';
                const elements = [...formProperty('elements')];
                const texts = elements.filter(textField);
                const ids = texts.filter(e => isIdentifier(e) && (selected === null || inputs[selected.loginIndex] === e));
                const controls = elements.filter(e => e.tagName === 'BUTTON' || ['submit','button','image'].includes(e.type));
                const loginIntent = controls.some(e => rendered(e) &&
                  /ログイン|サインイン|log\s*in|sign\s*in|次へ|continue|next/i.test(
                    e.textContent || e.value || e.getAttribute('aria-label') || e.getAttribute('alt') || ''));
                const registrationIntent = controls.some(e => rendered(e) &&
                  /新規登録|会員登録|アカウント作成|sign\s*up|register|create\s*account|(?:reset|change)\s*password|パスワード.*(?:変更|再設定)/i.test(
                    e.textContent || e.value || e.getAttribute('aria-label') || ''));
                if (registrationIntent || !loginIntent && (!pass || autocomplete(pass) !== 'current-password'))
                  return 'unsupported';
                // A lone opaque text field could be a CAPTCHA or tenant code. Positive ID evidence is required.
                if (ids.length > 1 || selected === null && texts.length > ids.length || candidates.some(e => e.form !== form))
                  return 'ambiguous';
                if (!pass && !loginIntent) return 'unsupported';
                const legacyForm = !knownAuthTail && legacyAction !== null && clean(formProperty('action')) === legacyAction;
                // Retain the narrow, previously reviewed adapter only for the legacy entry-route exception.
                if (legacyForm && (form.getAttribute('id') !== 'formLogin' || form.getAttribute('name') !== 'formLogin' ||
                    document.querySelectorAll('form[id="formLogin"], form[name="formLogin"]').length !== 1 ||
                    !pass || pass.name !== 'gkspw' || pass.id !== '!gkspw' || pass.parentElement.closest('form') !== form ||
                    ids.length !== 1 || ids[0].name !== 'gksid' || ids[0].id !== 'gksid' || ids[0].type !== 'text' ||
                    ids[0].parentElement.closest('form') !== form || !editable(ids[0]))) return 'unsupported';
                if (legacyForm && (ids[0].value || pass.value)) return 'occupied';
                const fields = [...ids, ...(pass ? [pass] : [])];
                // Hidden/decoy successful controls must not receive or shadow a chosen credential identity.
                if (fields.some(e => e.id && [...document.querySelectorAll('[id]')].filter(n => n.id === e.id).length !== 1 ||
                    e.name && elements.some(other => other !== e && !other.matches(':disabled') &&
                    other.name === e.name))) return 'ambiguous';
                if (pass && !ids.length && !$allowPasswordOnly) return 'pending';
                if (pass && !ids.length && candidates.length) return 'ambiguous';
                const stage = !pass ? 'identifier' : !ids.length ? 'password' :
                  $allowPasswordOnly && ids[0].value ? 'password_with_identifier' : 'combined';
                if (!pass && $allowPasswordOnly) return 'pending';
                if ((stage === 'combined' || stage === 'identifier') && ids.some(e => !editable(e)))
                  return 'unsupported';
                if ((ids.length && ids[0].value && stage !== 'password_with_identifier') ||
                    (pass && pass.value)) return 'occupied';
                $operation
              } catch (_) { return 'unsupported'; }
            })()
            """.trimIndent()
    }
}
