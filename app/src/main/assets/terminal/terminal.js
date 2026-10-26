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
window.hermesTerminal = Object.freeze({
    write(base64) { terminal.write(Uint8Array.from(atob(base64), c => c.charCodeAt(0))); },
    reset() { terminal.reset(); },
    configure(size, background, foreground, cursor) {
        terminal.options.fontSize = size;
        terminal.options.theme = { background, foreground, cursor, selectionBackground: cursor + "55" };
        document.body.style.background = background;
        terminal.element.style.backgroundColor = background;
        const viewport = terminal.element.querySelector(".xterm-viewport");
        if (viewport) viewport.style.backgroundColor = background;
        fit();
    },
    status() { fit(); return { cols: terminal.cols, rows: terminal.rows,
        applicationCursor: terminal.modes.applicationCursorKeysMode, reports: reports.splice(0) }; },
    bottom() { terminal.scrollToBottom(); },
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
    // Whole buffer as plain text (ignores any selection) plus the character offset under a
    // point of the view, given as fractions of its width and height.
    allText(fx, fy) {
        const buffer = terminal.buffer.active;
        const row = buffer.viewportY + Math.max(0, Math.min(terminal.rows - 1, Math.floor(fy * terminal.rows)));
        const col = Math.max(0, Math.floor(fx * terminal.cols));
        let text = "";
        let offset = -1;
        for (let i = 0; i < buffer.length; i++) {
            const line = buffer.getLine(i);
            if (i > 0 && !line?.isWrapped) text += "\n";
            const value = line?.translateToString(true) || "";
            if (i === row) offset = text.length + Math.min(col, value.length);
            text += value;
        }
        const limit = 131072;
        const cut = Math.max(0, text.length - limit);
        return { text: text.slice(cut), offset: offset < 0 ? text.length - cut : Math.max(0, offset - cut) };
    }
});
