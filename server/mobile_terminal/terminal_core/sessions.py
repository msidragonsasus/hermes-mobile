import atexit
import base64
import hashlib
import logging
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time
import uuid


log = logging.getLogger("hermes.mobile_terminal")


def session_processes(sid):
    """PIDs whose session id is sid (the shell and every job it started, any process group)."""
    pids = []
    for name in os.listdir("/proc"):
        if not name.isdigit():
            continue
        try:
            with open(f"/proc/{name}/stat", "rb") as stat:
                fields = stat.read().rsplit(b")", 1)[1].split()
            # Fields after the command: state, ppid, pgrp, session, ...
            # Zombies are already dead and only wait for their new parent to reap them.
            if int(fields[3]) == sid and fields[0] != b"Z":
                pids.append(int(name))
        except (OSError, IndexError, ValueError):
            continue
    return pids


def signal_all(pids, sig):
    for pid in pids:
        try:
            os.kill(pid, sig)
        except (ProcessLookupError, PermissionError):
            pass


class TerminalError(Exception):
    def __init__(self, code, status=409):
        self.code, self.status = code, status


class Session:
    """Bounded, memory-only output; one ordered input stream per PTY."""
    LIMIT = 1024 * 1024

    def __init__(self, session_id, owner, cols, rows):
        import fcntl
        import pty
        import pwd
        import struct
        import termios
        account = pwd.getpwuid(os.getuid())
        # Service accounts often have nologin as their SSH/login shell. The explicit
        # administrator opt-in grants this separate Dashboard shell capability.
        shell = os.environ.get("HERMES_MOBILE_TERMINAL_SHELL") or ("/bin/bash" if Path("/bin/bash").is_file() else "/bin/sh")
        if not os.path.isabs(shell) or not os.path.isfile(shell) or not os.access(shell, os.X_OK):
            raise OSError("terminal shell unavailable")
        self.id, self.owner = session_id, owner
        self.lock = threading.RLock()
        self.output = bytearray()
        self.start = 0
        self.last_input = 0
        self.last_digest = None
        self.input_state = "ready"
        self.touched = time.monotonic()
        self.closed = False
        self.disposed = False
        master, slave = pty.openpty()
        env = {key: value for key, value in os.environ.items()
               if key in {"PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ"}}
        env.update(HOME=account.pw_dir, USER=account.pw_name, LOGNAME=account.pw_name,
                   SHELL=shell, TERM="xterm-256color", COLORTERM="truecolor",
                   HISTFILE="/dev/null", HISTSIZE="0", SAVEHIST="0",
                   XDG_CACHE_HOME=str(Path(account.pw_dir) / ".cache"))
        try:
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
            self.process = subprocess.Popen(
                [sys.executable, "-I", str(Path(__file__).with_name("child.py")), shell],
                stdin=slave, stdout=slave, stderr=slave, cwd=account.pw_dir,
                env=env, start_new_session=True, close_fds=True)
        except BaseException:
            os.close(master)
            raise
        finally:
            os.close(slave)
        self.fd = master
        os.set_blocking(master, False)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        import select
        try:
            while True:
                select.select([self.fd], [], [], 0.2)
                with self.lock:
                    if self.disposed:
                        break
                    try:
                        chunk = os.read(self.fd, 16384)
                    except BlockingIOError:
                        continue
                    if not chunk:
                        break
                    self.output.extend(chunk)
                    excess = max(0, len(self.output) - self.LIMIT)
                    if excess:
                        del self.output[:excess]
                        self.start += excess
        except OSError:
            pass
        finally:
            with self.lock:
                self.closed = True
            # Reap the exited shell so it does not linger as a zombie.
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass

    def snapshot(self, offset=0):
        with self.lock:
            self.touched = time.monotonic()
            end = self.start + len(self.output)
            if offset > end:
                raise TerminalError("invalid_offset", 400)
            begin = max(offset, self.start)
            chunk = bytes(self.output[begin - self.start:begin - self.start + 65536])
            return dict(id=self.id, start=begin, next=begin + len(chunk),
                        truncated=offset < self.start, data=base64.b64encode(chunk).decode(),
                        closed=self.closed, last_input=self.last_input, input_state=self.input_state)

    def write(self, sequence, data):
        with self.lock:
            digest = hashlib.sha256(data).digest()
            if sequence == self.last_input and digest == self.last_digest:
                return dict(sequence=sequence, state=self.input_state)
            if sequence != self.last_input + 1:
                raise TerminalError("input_sequence_mismatch")
            if self.closed:
                raise TerminalError("session_closed")
            # Reserve before the first byte: a lost response must never replay input.
            self.last_input, self.last_digest = sequence, digest
            self.input_state = "uncertain"
            self.touched = time.monotonic()
            import select
            deadline = time.monotonic() + 2
            try:
                sent = 0
                while sent < len(data):
                    remaining = deadline - time.monotonic()
                    if remaining <= 0 or not select.select([], [self.fd], [], remaining)[1]:
                        raise OSError()
                    sent += os.write(self.fd, data[sent:])
                self.input_state = "accepted"
            except OSError:
                pass
            return dict(sequence=sequence, state=self.input_state)

    def resize(self, cols, rows):
        import fcntl
        import struct
        import termios
        with self.lock:
            if not self.closed:
                fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
            self.touched = time.monotonic()

    def close(self):
        with self.lock:
            if self.disposed:
                return
            self.disposed = True
            # start_new_session makes the shell a session leader, so its pid is the session id.
            # Background jobs live in their own process groups; signal the whole session.
            sid = self.process.pid
            signal_all(session_processes(sid), signal.SIGHUP)
            try:
                os.killpg(sid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            if not self.closed:
                self.closed = True
            try:
                os.close(self.fd)
            except OSError:
                pass
            self.output.clear()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass
        # Jobs that ignored SIGHUP (nohup, disown) are killed after a short grace period.
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline and session_processes(sid):
            time.sleep(0.05)
        signal_all(session_processes(sid), signal.SIGKILL)
        # SIGKILL is asynchronous; give the kernel a moment to finish tearing them down.
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline and session_processes(sid):
            time.sleep(0.02)


class Sessions:
    def __init__(self, factory=Session):
        self.sessions = {}
        self.lock = threading.RLock()
        self.factory = factory
        self.stopped = threading.Event()
        atexit.register(self.close)
        threading.Thread(target=self._collect, daemon=True).start()

    def _collect(self):
        while not self.stopped.wait(30):
            with self.lock:
                expired = [s for s in self.sessions.values() if time.monotonic() - s.touched > 1800]
                for session in expired:
                    self.sessions.pop(session.id)
            for session in expired:
                session.close()
                log.warning("mobile_terminal expire session=%s", session.id)

    def create(self, session_id, owner, cols, rows):
        return self.create_or_get(session_id, owner, cols, rows)[0]

    def create_or_get(self, session_id, owner, cols, rows):
        try:
            if str(uuid.UUID(session_id)) != session_id:
                raise ValueError()
        except ValueError:
            raise TerminalError("invalid_session_id", 400) from None
        with self.lock:
            if session_id in self.sessions:
                return self.get(session_id, owner), False
            # Exited shells keep their output readable but no longer use a slot.
            # The per-login limit ignores the profile part of the owner.
            live = [s for s in self.sessions.values() if not s.closed]
            account = owner.split(".", 1)[0]
            # Exited ones still hold up to 1 MiB of output each, so keep a hard total too.
            if len(self.sessions) >= 32 or len(live) >= 16 or sum(s.owner.split(".", 1)[0] == account for s in live) >= 4:
                raise TerminalError("session_limit", 429)
            session = self.factory(session_id, owner, cols, rows)
            self.sessions[session_id] = session
            return session, True

    def get(self, session_id, owner):
        with self.lock:
            session = self.sessions.get(session_id)
            if session is None or session.owner != owner:
                raise TerminalError("session_not_found", 404)
            return session

    def close(self):
        self.stopped.set()
        with self.lock:
            sessions = list(self.sessions.values())
            self.sessions.clear()
        for session in sessions:
            session.close()
