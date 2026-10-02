"""Give the installed TUI a real terminal; forward the test's keystrokes and output."""
import errno
import fcntl
import os
import pty
import select
import struct
import subprocess
import sys
import termios
import time

master, slave = pty.openpty()
fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 32, 100, 0, 0))
child = subprocess.Popen(sys.argv[1:], stdin=slave, stdout=slave, stderr=slave)
os.close(slave)
inputs = [master, sys.stdin.fileno()]
deadline = time.monotonic() + 20
try:
    while time.monotonic() < deadline:
        ready, _, _ = select.select(inputs, [], [], 0.1)
        for stream in ready:
            try:
                data = os.read(stream, 65536)
            except OSError as failure:
                if stream == master and failure.errno == errno.EIO:
                    data = b""
                else:
                    raise
            if stream == master:
                if not data:
                    sys.exit(child.wait(timeout=2))
                os.write(sys.stdout.fileno(), data)
            elif data:
                os.write(master, data)
            else:
                inputs.remove(stream)
                os.write(master, b"\x04")
    raise TimeoutError("Installed TUI did not finish within 20 seconds")
finally:
    if child.poll() is None:
        child.kill()
        child.wait()
    os.close(master)
