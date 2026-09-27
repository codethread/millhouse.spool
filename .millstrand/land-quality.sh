#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

git diff --check
# Local, CI and landing gates share Make's affected-test planner. Default base
# is main; callers may pass TEST_BASE=parent or TEST_FULL=1 as Make overrides.
exec sh scripts/with-test-lock.sh /tmp/millstrand-test.lock make quality "$@"
