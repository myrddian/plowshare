"""Collector-wide bounds are separate from batch, tool and agent-context bounds."""

MAX_TARGETS = 256
BATCH_SIZE = 32
MAX_PORTS = 16
MAX_PROBES = MAX_TARGETS * MAX_PORTS
MAX_DNS_ROWS = 4096
MAX_DNS_QUERIES = MAX_TARGETS * 256
MAX_CHANGES = MAX_PROBES + MAX_DNS_ROWS + MAX_TARGETS + 1
MAX_EVIDENCE_BYTES = 786432
# Read bound for a journal written before evidence moved to per-scan files.
MAX_JOURNAL_BYTES = 134217728
# The journal index holds receipt metadata only; evidence has one file per scan.
MAX_INDEX_BYTES = 8 * 1024 * 1024
# UTF-8 bound of one stored evidence document (MAX_EVIDENCE_BYTES counts UTF-16 units).
MAX_EVIDENCE_FILE_BYTES = 4 * 1024 * 1024
# Settled scans keep full local evidence for this long. The newest
# MIN_RETAINED_SCANS always stay, whatever their age, so comparison baselines and
# recent history survive a long stop. MAX_RETAINED_SCANS keeps a faster schedule
# below the journal's 1,000-scan fence.
RETAINED_SCAN_DAYS = 7
MIN_RETAINED_SCANS = 50
MAX_RETAINED_SCANS = 900
# Identities of scans that left the window, kept so a redelivery is not rescanned.
MAX_ARCHIVED_SCANS = 10000
MAX_CARRIED_ADDRESSES = 4096
TOOL_REPLY_BYTES = 16000
