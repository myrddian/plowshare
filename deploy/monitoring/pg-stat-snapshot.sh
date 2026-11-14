#!/bin/sh
# Capture pg_stat_statements to a timestamped CSV, or compare two captures.
#
#   pg-stat-snapshot.sh snapshot OUTPUT_DIR   (connection via the standard PG* variables)
#   pg-stat-snapshot.sh delta BEFORE.csv AFTER.csv
#
# Statements are identified by queryid; query text is never exported. The delta is
# AFTER minus BEFORE per queryid, sorted by total time, so run a workload between
# the two snapshots. Reset counters (pg_stat_statements_reset) between runs if the
# server restarted, since a restart makes AFTER smaller than BEFORE.
set -eu

usage() {
    echo "usage: $0 snapshot OUTPUT_DIR | delta BEFORE.csv AFTER.csv" >&2
    exit 2
}

[ $# -ge 1 ] || usage
mode=$1
shift

case "$mode" in
snapshot)
    [ $# -eq 1 ] || usage
    : "${PGHOST:?Set PGHOST (and PGUSER, PGDATABASE, PGPASSFILE) for the target database.}"
    test -d "$1" && test -w "$1" || { echo "OUTPUT_DIR must be an existing writable directory." >&2; exit 1; }
    out="$1/pg-stat-statements-$(date -u +%Y%m%dT%H%M%SZ).csv"
    psql -X -v ON_ERROR_STOP=1 -c "\\copy (SELECT queryid, calls, total_exec_time, mean_exec_time, rows, shared_blks_hit, shared_blks_read FROM pg_stat_statements ORDER BY total_exec_time DESC) TO STDOUT WITH (FORMAT csv, HEADER true)" >"$out"
    echo "$out"
    ;;
delta)
    [ $# -eq 2 ] || usage
    test -r "$1" && test -r "$2" || { echo "Both CSV files must be readable." >&2; exit 1; }
    awk -F, '
        FNR == 1 { next }
        NR == FNR { calls[$1] = $2; total[$1] = $3; rows[$1] = $5; hit[$1] = $6; read[$1] = $7; next }
        {
            dc = $2 - calls[$1]; dt = $3 - total[$1]
            if (dc <= 0) next
            printf "%s,%d,%.3f,%.3f,%d,%d,%d\n", $1, dc, dt, dt / dc, $5 - rows[$1], $6 - hit[$1], $7 - read[$1]
        }' "$1" "$2" | sort -t, -k3,3 -nr | { echo "queryid,calls,total_exec_time_ms,mean_exec_time_ms,rows,shared_blks_hit,shared_blks_read"; cat; }
    ;;
*)
    usage
    ;;
esac
