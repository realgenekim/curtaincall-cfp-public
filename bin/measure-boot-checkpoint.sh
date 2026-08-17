#!/usr/bin/env sh
set -eu

cache_path="./cache/store-checkpoint.json"
backup_dir=""
backup_path=""

now_ms() {
  python3 -c 'import time; print(time.monotonic_ns() // 1000000)'
}

measure() {
  label="$1"
  shift
  started="$(now_ms)"
  "$@"
  finished="$(now_ms)"
  printf '%s: %s ms\n' "$label" "$((finished - started))"
}

restore_cache() {
  if [ -n "$backup_path" ] && [ -f "$backup_path" ]; then
    mkdir -p "$(dirname "$cache_path")"
    mv "$backup_path" "$cache_path"
  fi
  if [ -n "$backup_dir" ] && [ -d "$backup_dir" ]; then
    rmdir "$backup_dir"
  fi
}

trap restore_cache EXIT INT TERM

measure "download-cache" env STORE_BACKEND=postgres make download-cache
measure "load-with-cache" env STORE_BACKEND=postgres clojure -M -e \
  "(require '[cfp-scheduler-killer.store :as store]) (store/load!) (shutdown-agents)"

backup_dir="$(mktemp -d "${TMPDIR:-/tmp}/store-checkpoint.XXXXXX")"
backup_path="$backup_dir/store-checkpoint.json"
mv "$cache_path" "$backup_path"

measure "load-without-cache" env STORE_BACKEND=postgres clojure -M -e \
  "(require '[cfp-scheduler-killer.store :as store]) (store/load!) (shutdown-agents)"

restore_cache
backup_dir=""
backup_path=""
trap - EXIT INT TERM
