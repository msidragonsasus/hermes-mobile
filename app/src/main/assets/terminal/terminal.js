"use strict";
// Only bundled code executes here. PTY output is bytes for the VT parser, never HTML or JavaScript.
// disableStdin also suppresses VT query replies in xterm. Keep the protocol enabled,
// but forward only allowlisted device reports; all human input uses Compose.
// Thin bar caret like a normal text field, also while the view is not focused.
const terminal = new Terminal({cursorBlink: true, cursorStyle: "bar", cursorWidth: 2, cursorInactiveStyle: "bar",
    scrollback: 3000, fontSize: 14, logLevel: "off"});
const fitting = new FitAddon.FitAddon();
terminal.loadAddon(fitting);
terminal.open(document.getElementById("terminal"));
terminal.attachCustomKeyEventHandler(() => false);
terminal.textarea.readOnly = true;
terminal.textarea.inputMode = "none";
// Only VT device/cursor replies can be returned to the host. User input uses the native editor.
const reports = [];
terminal.onData(data => {
    if (/^\x1b\[(?:[0-9]{1,3};[0-9]{1,3}R|[?>][0-9;]{1,40}c|0n)$/.test(data) && reports.length < 16) reports.push(data);
});
// OSC 52 / hyperlink escapes cannot access host clipboard, credentials or navigation.
terminal.parser.registerOscHandler(52, () => true);
terminal.parser.registerOscHandler(8, () => true);
// Tappable https links, also when a long URL wraps over several rows. The host validates
// and opens them; nothing here navigates.
function logicalLine(row) {
    const buffer = terminal.buffer.active;
    let start = row;
    while (start > 0 && buffer.getLine(start)?.isWrapped) start--;
    let text = "";
    const cells = [];
    for (let y = start; y < buffer.length; y++) {
        const line = buffer.getLine(y);
        if (!line || (y > start && !line.isWrapped)) break;
        for (let x = 0; x < line.length; x++) {
            const cell = line.getCell(x);
            if (!cell || cell.getWidth() === 0) continue;
            const chars = cell.getChars() || " ";
            for (let k = 0; k < chars.length; k++) cells.push({ x: x + 1, y: y + 1 });
            text += chars;
        }
    }
    return { text, cells };
}
terminal.registerLinkProvider({
    provideLinks(y, callback) {
        const { text, cells } = logicalLine(y - 1);
        const links = [];
        for (const match of text.matchAll(/https:\/\/[^\s<>"'`]+/g)) {
            const url = match[0].replace(/[.,;:!?)\]}]+$/, "");
            if (url.length > 8192) continue;
            const start = cells[match.index];
            const end = cells[match.index + url.length - 1];
            if (!start || !end || y < start.y || y > end.y) continue;
            links.push({
                range: { start, end },
                text: url,
                decorations: { underline: true, pointerCursor: true },
                activate(event, value) { window.hermesHost?.openLink(value); },
            });
        }
        callback(links.length ? links : undefined);
    }
});
function fit() {
    const size = fitting.proposeDimensions();
    if (size) terminal.resize(Math.max(20, Math.min(240, size.cols)), Math.max(4, Math.min(120, size.rows)));
}
new ResizeObserver(fit).observe(document.body);
function atBottom() {
    const buffer = terminal.buffer.active;
    return buffer.viewportY >= buffer.baseY;
}
// Output that arrived while older lines were shown; cleared once the bottom is visible again.
let unseen = false;
// One owner for touch: xterm 6 registers no touch scroll target, and the host's native
// long-click would race a slow drag. Captured here, before xterm's document-level gesture
// listener, so one drag scrolls once and never turns into arrow keys or shell history.
const surface = document.getElementById("terminal");
const SLOP = 8;
const LONG_PRESS = 450;
let touch = null;
let fling = 0;
function screenRect() {
    return terminal.element?.querySelector(".xterm-screen")?.getBoundingClientRect();
}
function rowHeight() {
    const rect = screenRect();
    return rect && rect.height > 0 ? rect.height / terminal.rows : 16;
}
// Cell under a point of the screen element itself, so the inset is not mapped as text.
function cellAt(x, y) {
    const rect = screenRect();
    const clamp = (value, max) => Math.max(0, Math.min(max, value));
    if (!rect || rect.width <= 0 || rect.height <= 0) return { row: terminal.buffer.active.viewportY, col: 0 };
    const col = clamp(Math.floor((x - rect.left) / (rect.width / terminal.cols)), terminal.cols - 1);
    const row = clamp(Math.floor((y - rect.top) / (rect.height / terminal.rows)), terminal.rows - 1);
    return { row: terminal.buffer.active.viewportY + row, col };
}
// Positive pixels show newer output. Fractions of a row carry over so slow drags still move.
function scrollPixels(touchState, pixels) {
    touchState.rest += pixels;
    const height = rowHeight();
    const lines = Math.trunc(touchState.rest / height);
    if (!lines) return false;
    touchState.rest -= lines * height;
    const before = terminal.buffer.active.viewportY;
    terminal.scrollLines(lines);
    return terminal.buffer.active.viewportY !== before;
}
function stopFling() { fling++; }
function startFling(velocity) {
    const id = ++fling;
    const state = { rest: 0 };
    let last = performance.now();
    const step = () => {
        if (id !== fling) return;
        const now = performance.now();
        const elapsed = Math.max(1, now - last);
        last = now;
        velocity *= Math.pow(0.95, elapsed / 16);
        const moved = scrollPixels(state, velocity * elapsed);
        if (Math.abs(velocity) < 0.05 || (!moved && (atBottom() || terminal.buffer.active.viewportY === 0))) return;
        requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
}
function endTouch(event, allowFling) {
    event.stopPropagation();
    const current = touch;
    touch = null;
    if (!current) return;
    clearTimeout(current.timer);
    // A tap stays untouched so xterm's link provider still gets its click.
    if (current.pressed || current.moved) event.preventDefault();
    if (!allowFling || !current.moved || current.pressed) return;
    const now = performance.now();
    const recent = current.samples.filter(sample => now - sample.time <= 100);
    if (recent.length < 2) return;
    const first = recent[0];
    const last = recent[recent.length - 1];
    const velocity = -(last.y - first.y) / Math.max(1, last.time - first.time);
    if (Math.abs(velocity) > 0.3) startFling(velocity);
}
surface.addEventListener("touchstart", event => {
    stopFling();
    event.stopPropagation();
    if (touch) clearTimeout(touch.timer);
    touch = null;
    if (event.touches.length !== 1) return;
    const point = event.touches[0];
    const current = { x: point.clientX, y: point.clientY, lastY: point.clientY, rest: 0, moved: false, pressed: false,
        samples: [{ y: point.clientY, time: performance.now() }] };
    current.timer = setTimeout(() => {
        if (touch !== current || current.moved) return;
        current.pressed = true;
        const cell = cellAt(current.x, current.y);
        window.hermesHost?.longPress(cell.row, cell.col);
    }, LONG_PRESS);
    touch = current;
}, { capture: true, passive: true });
surface.addEventListener("touchmove", event => {
    event.stopPropagation();
    if (!touch || event.touches.length !== 1) return;
    event.preventDefault();
    const point = event.touches[0];
    if (!touch.moved) {
        if (touch.pressed || Math.hypot(point.clientX - touch.x, point.clientY - touch.y) <= SLOP) return;
        touch.moved = true;
        clearTimeout(touch.timer);
    }
    scrollPixels(touch, touch.lastY - point.clientY);
    touch.lastY = point.clientY;
    touch.samples.push({ y: point.clientY, time: performance.now() });
    if (touch.samples.length > 20) touch.samples.shift();
}, { capture: true, passive: false });
surface.addEventListener("touchend", event => endTouch(event, true), { capture: true, passive: false });
surface.addEventListener("touchcancel", event => endTouch(event, false), { capture: true, passive: false });
surface.addEventListener("contextmenu", event => event.preventDefault(), { capture: true });
window.hermesTerminal = Object.freeze({
    write(base64) {
        terminal.write(Uint8Array.from(atob(base64), c => c.charCodeAt(0)));
        if (!atBottom()) unseen = true;
    },
    reset() { terminal.reset(); unseen = false; },
    configure(size, background, foreground, cursor) {
        terminal.options.fontSize = size;
        terminal.options.theme = { background, foreground, cursor, selectionBackground: cursor + "55" };
        document.body.style.background = background;
        terminal.element.style.backgroundColor = background;
        const viewport = terminal.element.querySelector(".xterm-viewport");
        if (viewport) viewport.style.backgroundColor = background;
        fit();
    },
    status() {
        fit();
        const bottom = atBottom();
        if (bottom) unseen = false;
        return { cols: terminal.cols, rows: terminal.rows, applicationCursor: terminal.modes.applicationCursorKeysMode,
            reports: reports.splice(0), atBottom: bottom, unseen, alternate: terminal.buffer.active.type === "alternate" };
    },
    bottom() { stopFling(); terminal.scrollToBottom(); unseen = false; },
    // Scrolls the view only; never sends Page Up/Down to the shell.
    page(amount) { stopFling(); terminal.scrollPages(Math.max(-1, Math.min(1, amount | 0))); },
    text() {
        if (terminal.hasSelection()) return terminal.getSelection();
        const buffer = terminal.buffer.active;
        let text = "";
        for (let i = 0; i < buffer.length; i++) {
            const line = buffer.getLine(i);
            if (i > 0 && !line?.isWrapped) text += "\n";
            text += line?.translateToString(true) || "";
        }
        return text.slice(-131072);
    },
    // Whole buffer as plain text (ignores any selection) for the frozen selection view: the
    // character offset of buffer cell (row, col), or of the visible top when row < 0, and the
    // offset where the visible top row starts so the frozen copy opens where the user was.
    snapshot(row, col) {
        stopFling();
        const buffer = terminal.buffer.active;
        const topRow = buffer.viewportY;
        const endRow = Math.min(buffer.length, topRow + terminal.rows);
        const target = row < 0 ? topRow : Math.max(0, Math.min(buffer.length - 1, row | 0));
        const column = row < 0 ? 0 : Math.max(0, col | 0);
        let text = "";
        let offset = -1;
        let top = -1;
        let viewEnd = -1;
        for (let i = 0; i < buffer.length; i++) {
            const line = buffer.getLine(i);
            if (i > 0 && !line?.isWrapped) text += "\n";
            if (i === endRow) viewEnd = text.length;
            const value = line?.translateToString(true) || "";
            if (i === topRow) top = text.length;
            if (i === target) offset = text.length + Math.min(cellOffset(line, column), value.length);
            text += value;
        }
        if (viewEnd < 0) viewEnd = text.length;
        if (offset < 0) offset = text.length;
        if (top < 0) top = text.length;
        const [start, end] = textWindow(text, Math.min(top, offset), Math.max(viewEnd, offset));
        return { text: text.slice(start, end), offset: offset - start, top: top - start };
    }
});
// UTF-16 offset of buffer column `column` within the line's text: a wide character spans two
// cells (the second has width 0) and one cell may hold a surrogate pair or combining marks.
function cellOffset(line, column) {
    if (!line) return 0;
    const cell = terminal.buffer.active.getNullCell?.();
    let offset = 0;
    for (let x = 0; x < line.length; x++) {
        const current = line.getCell(x, cell);
        const width = current ? current.getWidth() : 1;
        if (width === 0) continue;
        if (column < x + width) return offset;
        offset += (current?.getChars() || " ").length;
    }
    return offset;
}
// At most 131072 UTF-16 units of `text` that contain [from, to] (the visible rows and the
// pressed character), centered on them, cut on line boundaries and never inside a surrogate pair.
function textWindow(text, from, to) {
    const limit = 131072;
    if (text.length <= limit) return [0, text.length];
    const high = code => code >= 0xd800 && code <= 0xdbff;
    const low = code => code >= 0xdc00 && code <= 0xdfff;
    const span = Math.min(limit, Math.max(0, to - from));
    let start = Math.max(0, from - Math.floor((limit - span) / 2));
    let end = Math.min(text.length, start + limit);
    start = Math.max(0, end - limit);
    const lineStart = text.indexOf("\n", start - 1);
    if (start > 0 && lineStart >= 0 && lineStart < from) start = lineStart + 1;
    const lineEnd = text.lastIndexOf("\n", end);
    if (end < text.length && lineEnd >= to) end = lineEnd;
    if (start > 0 && low(text.charCodeAt(start))) start++;
    if (end < text.length && high(text.charCodeAt(end - 1))) end--;
    return [start, end];
}
