#!/usr/bin/env bash
# Bound one Kaocha --watch stream into ANSI-free, machine-readable runs.
set -uo pipefail

run=1
printf '▶▶▶ TEST WINDOW %d OPEN %s\n' "$run" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
while IFS= read -r line; do
  clean=$(printf '%s' "$line" | sed $'s/\x1b\[[0-9;]*m//g')
  printf '%s\n' "$clean"
  case "$clean" in
    *" tests, "*" assertions"*)
      finished_epoch=$(date +%s)
      printf '◀◀◀ TEST RUN %d VERDICT epoch=%d: %s\n\n' \
        "$run" "$finished_epoch" "$clean"
      [ -z "${VERDICT_STAMP:-}" ] || touch "$VERDICT_STAMP"
      run=$((run + 1))
      printf '▶▶▶ TEST WINDOW %d OPEN %s\n' "$run" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      ;;
  esac
done
