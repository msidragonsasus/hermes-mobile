/* Run from the repository root with plain Node (no packages):
 * node app/src/test/resources/terminal/terminal-touch.test.cjs
 * Loads the actual bundled terminal.js against a fake xterm/DOM and drives synthetic touches.
 * Synthetic output only; no network, server or credentials.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const script = fs.readFileSync('app/src/main/assets/terminal/terminal.js', 'utf8');
const css = fs.readFileSync('app/src/main/assets/terminal/terminal.css', 'utf8');

// Screen geometry inside the padded view: 80x24 cells of 10x20 CSS px, offset by the inset.
const SCREEN = { left: 12, top: 8, width: 800, height: 480 };

function element(name) {
  const listeners = {};
  return {
    name, style: {}, children: [], listeners,
    addEventListener(type, fn, options) { (listeners[type] ||= []).push({ fn, options }); },
    appendChild(child) { this.children.push(child); },
    querySelector(selector) { return selector === '.xterm-screen' ? this.screen : selector === '.xterm-viewport' ? { style: {} } : null; },
    getBoundingClientRect() { return { ...SCREEN }; },
  };
}

// A buffer line as xterm exposes it: cells with chars and width (0 = wide-char continuation).
function fakeLine(spec, wrapped = false) {
  const cells = typeof spec === 'string' ? Array.from(spec, ch => [ch, 1]) : spec;
  return {
    isWrapped: wrapped,
    length: cells.length,
    getCell: x => (x >= 0 && x < cells.length ? { getChars: () => cells[x][0], getWidth: () => cells[x][1] } : undefined),
    translateToString: trim => {
      const text = cells.filter(([, w]) => w > 0).map(([c]) => c || ' ').join('');
      return trim ? text.replace(/\s+$/, '') : text;
    },
  };
}

function setup(options = {}) {
  let clock = 0;
  const timers = [];
  const frames = [];
  const calls = { longPress: [], openLink: [] };
  const container = element('terminal');
  const xtermElement = element('xterm');
  xtermElement.screen = element('screen');
  const lines = options.lines || Array.from({ length: 124 }, (_, i) => fakeLine(`line${i}`));
  class FakeTerminal {
    constructor(options) {
      this.options = { ...options };
      this.cols = 80; this.rows = 24;
      this.data = []; this.written = []; this.scrolled = [];
      this.textarea = {};
      this.modes = { applicationCursorKeysMode: false };
      this.parser = { registerOscHandler() {} };
      const base = Math.max(0, lines.length - 24);
      const active = { type: 'normal', viewportY: base, baseY: base,
        get length() { return lines.length; },
        getNullCell: () => ({}),
        getLine: i => (i >= 0 && i < lines.length ? lines[i] : undefined) };
      this.buffer = { active };
      FakeTerminal.instance = this;
    }
    loadAddon() {}
    open() { this.element = xtermElement; }
    attachCustomKeyEventHandler() {}
    onData(fn) { this.dataHandler = fn; }
    registerLinkProvider() {}
    resize() {}
    reset() {}
    hasSelection() { return false; }
    scrollLines(n) {
      this.scrolled.push(n);
      const b = this.buffer.active;
      b.viewportY = Math.max(0, Math.min(b.baseY, b.viewportY + n));
    }
    scrollPages(n) { this.scrollLines(n * (this.rows - 1)); }
    scrollToBottom() { this.buffer.active.viewportY = this.buffer.active.baseY; }
    // Like xterm: a new line moves the viewport only when it is already at the bottom.
    write(data) {
      this.written.push(data);
      const b = this.buffer.active;
      const follow = b.viewportY >= b.baseY;
      lines.push(fakeLine('new')); b.baseY++;
      if (follow) b.viewportY = b.baseY;
    }
  }
  const context = {
    Terminal: FakeTerminal,
    FitAddon: { FitAddon: class { proposeDimensions() { return { cols: 80, rows: 24 }; } } },
    ResizeObserver: class { observe() {} },
    document: { getElementById: id => (id === 'terminal' ? container : null), body: { style: {} } },
    performance: { now: () => clock },
    setTimeout: (fn, ms) => { const t = { fn, at: clock + ms }; timers.push(t); return t; },
    clearTimeout: t => { const i = timers.indexOf(t); if (i >= 0) timers.splice(i, 1); },
    requestAnimationFrame: fn => { frames.push(fn); return frames.length; },
    cancelAnimationFrame: () => { frames.length = 0; },
    atob: value => Buffer.from(value, 'base64').toString('binary'),
    Uint8Array, JSON, Math, Object, Array, String,
  };
  context.window = context;
  context.window.hermesHost = {
    longPress: (row, col) => calls.longPress.push([row, col]),
    openLink: url => calls.openLink.push(url),
  };
  vm.createContext(context);
  vm.runInContext(script, context, { filename: 'terminal.js' });
  const term = FakeTerminal.instance;
  function advance(ms) {
    const end = clock + ms;
    for (;;) {
      timers.sort((a, b) => a.at - b.at);
      if (!timers.length || timers[0].at > end) break;
      const t = timers.shift();
      clock = t.at;
      t.fn();
    }
    clock = end;
  }
  function frame(ms = 16) {
    advance(ms);
    const pending = frames.splice(0);
    pending.forEach(fn => fn(clock));
    return pending.length;
  }
  function fire(type, x, y, touches = 1) {
    const event = {
      type, defaultPrevented: false, propagationStopped: false,
      touches: type === 'touchend' || type === 'touchcancel' ? [] : Array.from({ length: touches }, () => ({ clientX: x, clientY: y })),
      changedTouches: [{ clientX: x, clientY: y }],
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this.propagationStopped = true; },
    };
    for (const { fn, options } of container.listeners[type] || []) {
      assert.ok(options === true || options?.capture === true, `${type} must be handled in capture phase before xterm`);
      if (type === 'touchmove') assert.equal(options.passive, false, 'touchmove must be able to cancel native scrolling');
      fn(event);
    }
    return event;
  }
  return { term, calls, fire, advance, frame, api: context.window.hermesTerminal, container };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('one-finger drag scrolls the buffer once, never as keys or twice through xterm', () => {
  const { term, fire, advance, calls } = setup();
  const start = fire('touchstart', 300, 200);
  assert.equal(start.propagationStopped, true, 'xterm document gesture must not see the touch');
  for (let y = 210; y <= 300; y += 10) { advance(16); const move = fire('touchmove', 300, y); assert.equal(move.defaultPrevented, true); }
  advance(16);
  fire('touchend', 300, 300);
  const total = term.scrolled.reduce((a, b) => a + b, 0);
  assert.equal(total, -5, 'finger down 100px with 20px rows shows 5 older lines');
  assert.deepEqual(term.data, []);
  assert.deepEqual(calls.longPress, []);
});

test('finger up scrolls toward newer output and stops at the bottom', () => {
  const { term, fire, advance, api } = setup();
  api.page(-1);
  const before = term.buffer.active.viewportY;
  fire('touchstart', 300, 400);
  for (let y = 380; y >= 340; y -= 20) { advance(16); fire('touchmove', 300, y); }
  fire('touchcancel', 300, 340);
  assert.equal(term.buffer.active.viewportY, before + 3);
});

test('hold without moving opens selection at the cell under the finger inside the inset', () => {
  const { term, fire, advance, calls } = setup();
  fire('touchstart', 12 + 10 * 7 + 3, 8 + 20 * 2 + 5);
  advance(600);
  assert.deepEqual(calls.longPress, [[term.buffer.active.viewportY + 2, 7]]);
  const end = fire('touchend', 85, 53);
  assert.equal(end.defaultPrevented, true, 'no click after a long press');
  assert.deepEqual(term.scrolled, []);
  // A press in the padding maps to the nearest cell, not outside the buffer.
  fire('touchstart', 2, 1);
  advance(600);
  assert.deepEqual(calls.longPress[1], [term.buffer.active.viewportY, 0]);
});

test('starting to scroll cancels the long press', () => {
  const { fire, advance, calls } = setup();
  fire('touchstart', 300, 200);
  advance(100);
  fire('touchmove', 300, 230);
  advance(600);
  fire('touchend', 300, 230);
  assert.deepEqual(calls.longPress, []);
});

test('a quick swipe keeps scrolling briefly in the same direction and then stops', () => {
  const { term, fire, advance, frame } = setup();
  fire('touchstart', 300, 100);
  for (let y = 140; y <= 300; y += 40) { advance(16); fire('touchmove', 300, y); }
  const dragged = term.scrolled.length;
  fire('touchend', 300, 300);
  let count = 0;
  while (frame() && count < 1000) count++;
  assert.ok(count < 1000, 'fling must end');
  const extra = term.scrolled.slice(dragged);
  assert.ok(extra.length > 0, 'fling continues');
  assert.ok(extra.every(n => n < 0), 'fling keeps the drag direction');
});

test('a tap stays a tap for xterm links and a new touch stops a fling', () => {
  const { term, fire, advance, frame } = setup();
  const tap = fire('touchstart', 300, 200);
  advance(80);
  const up = fire('touchend', 300, 200);
  assert.equal(up.defaultPrevented, false, 'compat click must reach the link provider');
  assert.equal(tap.defaultPrevented, false);
  fire('touchstart', 300, 100);
  for (let y = 140; y <= 300; y += 40) { advance(16); fire('touchmove', 300, y); }
  fire('touchend', 300, 300);
  frame();
  fire('touchstart', 300, 300);
  const stopped = term.scrolled.length;
  frame(); frame();
  assert.equal(term.scrolled.length, stopped);
  fire('touchend', 300, 300);
});

test('two-finger touches are ignored instead of scrolling', () => {
  const { term, fire, advance } = setup();
  fire('touchstart', 300, 200, 2);
  advance(16);
  fire('touchmove', 300, 300, 2);
  fire('touchend', 300, 300);
  assert.deepEqual(term.scrolled, []);
});

test('incoming output keeps an older viewport and reports unseen output until the bottom', () => {
  const { term, api } = setup();
  assert.equal(api.status().atBottom, true);
  api.page(-1);
  const kept = term.buffer.active.viewportY;
  api.write(Buffer.from('more\r\n').toString('base64'));
  assert.equal(term.buffer.active.viewportY, kept);
  let status = api.status();
  assert.equal(status.atBottom, false);
  assert.equal(status.unseen, true);
  assert.equal(status.alternate, false);
  api.bottom();
  status = api.status();
  assert.equal(status.atBottom, true);
  assert.equal(status.unseen, false);
  api.write(Buffer.from('tail\r\n').toString('base64'));
  assert.equal(api.status().unseen, false);
});

test('page affordance scrolls by pages without sending input', () => {
  const { term, api } = setup();
  api.page(-1);
  api.page(1);
  assert.deepEqual(term.scrolled, [-23, 23]);
  assert.deepEqual(term.data, []);
});

test('snapshot keeps the visible top and the pressed character', () => {
  const { term, api } = setup();
  api.page(-1);
  const top = term.buffer.active.viewportY;
  const pressed = api.snapshot(top + 2, 3);
  const lineStart = pressed.text.split('\n').slice(0, top).join('\n').length + 1;
  assert.equal(pressed.top, lineStart);
  assert.equal(pressed.text.slice(pressed.offset, pressed.offset + 1), `line${top + 2}`[3]);
  const menu = api.snapshot(-1, -1);
  assert.equal(menu.offset, menu.top, 'menu selection opens at the visible top, not the tail');
});

test('pressed cell maps to the right character after wide, emoji and combining cells', () => {
  // Cells as xterm stores them: wide chars take two cells (second width 0), one cell may hold
  // a surrogate pair or a base letter plus a combining mark.
  const wide = fakeLine([['日', 2], ['', 0], ['本', 2], ['', 0], [' ', 1], ['a', 1], ['b', 1], ['c', 1], [' ', 1], ['d', 1], ['e', 1], ['f', 1]]);
  const emoji = fakeLine([['😀', 2], ['', 0], ['x', 1], ['y', 1], [' ', 1], ['z', 1]]);
  const combining = fakeLine([['e\u0301', 1], ['t', 1], ['é', 1], [' ', 1], ['q', 1]]);
  const { api } = setup({ lines: [wide, emoji, combining, ...Array.from({ length: 30 }, (_, i) => fakeLine(`pad${i}`))] });
  const at = (row, col) => { const s = api.snapshot(row, col); return s.text[s.offset]; };
  assert.equal(at(0, 7), 'c', 'cell 7 of "日本 abc def" is c, not the UTF-16 index 7 (e)');
  assert.equal(at(0, 1), '日', 'continuation cell belongs to its wide char');
  assert.equal(at(0, 3), '本');
  assert.equal(at(0, 9), 'd');
  assert.equal(at(1, 2), 'x', 'emoji is one cell of two UTF-16 units');
  assert.equal(at(1, 5), 'z');
  assert.equal(at(2, 1), 't', 'combining sequence is one cell of two UTF-16 units');
  assert.equal(at(2, 4), 'q');
  const past = api.snapshot(0, 60);
  assert.equal(past.offset, '日本 abc def'.length, 'beyond the line end lands at its trimmed end');
});

test('large buffers keep the viewport and pressed row instead of only the tail', () => {
  const lines = Array.from({ length: 2900 }, (_, i) => fakeLine(`row-${String(i).padStart(4, '0')} ` + 'x'.repeat(60)));
  const { term, api } = setup({ lines });
  const total = lines.map(l => l.translateToString(true)).join('\n').length;
  assert.ok(total > 131072, 'fixture exceeds the window');
  for (const topRow of [0, 1500, term.buffer.active.baseY]) {
    term.buffer.active.viewportY = topRow;
    for (const [row, col] of [[topRow, 0], [topRow + 5, 3], [-1, -1]]) {
      const s = api.snapshot(row, col);
      assert.ok(s.text.length <= 131072, 'bounded');
      assert.ok(s.offset >= 0 && s.offset <= s.text.length && s.top >= 0 && s.top <= s.text.length);
      assert.ok(s.text.startsWith(`row-${String(topRow).padStart(4, '0')}`, s.top), `top row ${topRow} kept`);
      const expected = row < 0 ? topRow : row;
      const lineStart = s.text.lastIndexOf('\n', s.offset - 1) + 1;
      assert.ok(s.text.startsWith(`row-${String(expected).padStart(4, '0')}`, lineStart), `pressed row ${expected}`);
      if (row >= 0) assert.equal(s.text[s.offset], `row-${String(expected).padStart(4, '0')}`[col]);
      assert.ok(s.text.startsWith('row-') && (s.text.endsWith('x') || s.text.endsWith('new')), 'cut on line boundaries');
    }
  }
  term.buffer.active.viewportY = 0;
  assert.ok(api.snapshot(0, 0).text.startsWith('row-0000'), 'row 0 present while viewing the top');
});

test('window cut never splits a surrogate pair', () => {
  // 30 emoji + "z" per line: a cut at length - 131072 would land on a low surrogate.
  const lines = Array.from({ length: 2900 }, () => fakeLine([...Array.from({ length: 30 }, () => ['😀', 1]), ['z', 1]]));
  const { term, api } = setup({ lines });
  term.buffer.active.viewportY = 1400;
  const s = api.snapshot(1400, 2);
  const code = s.text.charCodeAt(0);
  assert.ok(!(code >= 0xdc00 && code <= 0xdfff), 'no lone low surrogate at start');
  const last = s.text.charCodeAt(s.text.length - 1);
  assert.ok(!(last >= 0xd800 && last <= 0xdbff), 'no lone high surrogate at end');
  assert.equal(s.text.codePointAt(s.offset), 0x1f600);
});

test('live terminal has a real inset that fit can measure', () => {
  const rule = css.match(/\.xterm\s*\{([^}]*)\}/)[1];
  const padding = rule.match(/padding:\s*(\d+)px\s+(\d+)px/);
  assert.ok(padding, 'vertical and horizontal padding');
  const [vertical, horizontal] = [Number(padding[1]), Number(padding[2])];
  assert.ok(vertical >= 8 && horizontal >= 12);
  assert.match(rule, new RegExp(`height:\\s*calc\\(100%\\s*-\\s*${vertical * 2}px\\)`));
  assert.match(css, /touch-action:\s*none/);
});

let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log(`PASS ${name}`); } catch (error) { failed++; console.log(`FAIL ${name}\n  ${error.message}`); }
}
console.log(`${tests.length - failed}/${tests.length} passed`);
process.exit(failed ? 1 : 0);
