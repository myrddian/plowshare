"""Sanitize retained application logs without printing runtime credentials."""
import re
import sys

for line in sys.stdin:
    line = re.sub(r'(?i)(token=)[^\s&#]+', r'\1[REDACTED]', line)
    line = re.sub(r'(?i)(\bbearer\s+)\S+', r'\1[REDACTED]', line)
    line = re.sub(r'(?i)((?:password|api[_-]?key|access[_-]?token|refresh[_-]?token)\s*[:=]\s*)[^\s,;]+', r'\1[REDACTED]', line)
    sys.stdout.write(line)
