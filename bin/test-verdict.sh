#!/usr/bin/env bash
# Latest watch result. Exit: 0=current green, 1=current red, 2=not current.
set -euo pipefail

log="${TESTLOG:-00TESTLOG.txt}"
pid_file="${TESTWATCH_PID:-.testwatch.pid}"
stamp="${TESTWATCH_STAMP:-.testwatch-verdict}"

[ -s "$log" ] || { echo "UNAVAILABLE: no test log (run make runtests-log)"; exit 2; }
[ -s "$pid_file" ] || { echo "UNAVAILABLE: watcher is not running"; exit 2; }
watch_pid=$(cat "$pid_file")
kill -0 "$watch_pid" 2>/dev/null || { echo "UNAVAILABLE: watcher pid $watch_pid is dead"; exit 2; }

last=$(awk '/^◀◀◀ TEST RUN [0-9]+ VERDICT/{run=$4} END{print run}' "$log")
[ -n "$last" ] || { echo "IN_PROGRESS: initial run has no verdict"; exit 2; }

extract() {
  awk -v run="$last" \
    '$0 ~ ("^▶▶▶ TEST WINDOW " run " OPEN"){on=1}
     on{print}
     $0 ~ ("^◀◀◀ TEST RUN " run " VERDICT"){exit}' "$log"
}

if [ "${1:-}" = "--full" ]; then
  extract
else
  extract | grep -E '^▶▶▶|^◀◀◀|FAIL|ERROR|expected:|actual:|Expected:|Actual:' || true
fi

[ -e "$stamp" ] || { echo "IN_PROGRESS: no completed-run stamp"; exit 2; }
newer=$(find src test resources -type f \
  \( -name '*.clj' -o -name '*.cljc' -o -name '*.edn' \) \
  -newer "$stamp" -print -quit 2>/dev/null || true)
[ -z "$newer" ] || { echo "IN_PROGRESS: source changed after run $last ($newer)"; exit 2; }

verdict=$(awk -v run="$last" '$0 ~ ("^◀◀◀ TEST RUN " run " VERDICT"){print; exit}' "$log")
failures=$(printf '%s\n' "$verdict" | sed -n 's/.* \([0-9][0-9]*\) failures.*/\1/p')
[ -n "$failures" ] || { echo "UNAVAILABLE: verdict is unparsable"; exit 2; }
[ "$failures" -eq 0 ] || exit 1
