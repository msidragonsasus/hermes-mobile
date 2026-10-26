"""Acquire the slave PTY as controlling terminal, then replace this process."""
import fcntl
import os
import sys
import termios

fcntl.ioctl(0, termios.TIOCSCTTY, 0)
os.execv(sys.argv[1], [sys.argv[1], "-i"])
