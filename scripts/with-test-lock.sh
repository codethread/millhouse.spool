#!/bin/sh
set -eu

lock_path=${1:?lock path is required}
shift
test "$#" -gt 0

# Acquire on this shell's descriptor so the successful attempt keeps the lock
# through exec. A probe that releases it before running the suite races peers.
exec 9>"$lock_path"
attempt=1
while [ "$attempt" -le 10 ]; do
  echo "test lock: waiting for $lock_path (attempt $attempt/10, up to 180s)" >&2
  if flock -w 180 -E 75 9; then
    echo "test lock: acquired; running $*" >&2
    exec "$@"
  else
    status=$?
  fi
  # Only acquisition conflicts retry. The command above replaces this shell,
  # so even a suite exiting 75 can never be mistaken for lock contention.
  if [ "$status" -ne 75 ]; then
    echo "test lock: acquisition failed with exit $status" >&2
    exit "$status"
  fi
  attempt=$((attempt + 1))
done

echo "test lock: acquisition exhausted after 10 attempts; command never started" >&2
exit 75
