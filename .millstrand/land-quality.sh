#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

git diff --check
exec flock -w 180 /tmp/millstrand-test.lock make quality "$@"
