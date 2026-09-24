#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$root"
exec clojure -Srepro -M -m millhouse.consumer-deps "$@"
