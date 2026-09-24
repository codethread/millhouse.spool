#!/usr/bin/env bash
# Resolve a remote candidate outside every source checkout, then exercise its
# unchanged package layout and native installation using disposable state.
set -euo pipefail
sha=${1:?usage: verify-distribution.sh MILLHOUSE_SHA [CONSUMER_CHECKOUT...]}
shift
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected a full immutable SHA' >&2; exit 2; }
consumers=("$@")
ws=$(mktemp -d)
trap 'rm -rf "${ws:?}"' EXIT
mkdir -p "$ws/consumer" "$ws/pi" "$ws/codex"
cat > "$ws/consumer/deps.edn" <<EOF
{:paths []
 :deps {codethread/config
        {:git/url "https://github.com/codethread/millhouse.spool.git"
         :git/sha "$sha" :deps/root "spools/config"}
        millhouse.spools/chime
        {:git/url "https://github.com/codethread/millhouse.spool.git"
         :git/sha "$sha" :deps/root "spools/chime"}
        millhouse.spools/cron
        {:git/url "https://github.com/codethread/millhouse.spool.git"
         :git/sha "$sha" :deps/root "spools/cron"}
        millhouse.spools/auto-review
        {:git/url "https://github.com/codethread/millhouse.spool.git"
         :git/sha "$sha" :deps/root "spools/auto-review"}}}
EOF
cd "$ws/consumer"
clojure -Srepro -Spath > "$ws/classpath"
# No old sibling checkout or independently pinned old package may supply code.
if rg '(harnesses\.spool|devflow\.spool|codethread\.spool|/dev/projects/)' "$ws/classpath"; then
  echo 'Distribution escaped its published package closure' >&2
  exit 1
fi
published=$(clojure -Srepro -M -e '
(require (quote [millstrand.test.alpha :as t]))
(println (-> (t/spool-checkout-root "ct/spools/codethread/bootstrap.clj")
             .getParentFile .getParentFile .getCanonicalPath))')
test "$(git -C "$published" rev-parse HEAD)" = "$sha"
# The bootstrap closure must resolve the imported packages from this revision,
# not from a second historical Git-library checkout with the same namespaces.
python3 - "$ws/classpath" "$sha" <<'PY'
from pathlib import Path
import os, subprocess, sys
paths = [Path(p).resolve() for p in Path(sys.argv[1]).read_text().strip().split(os.pathsep)]
for package in ('harnesses', 'devflow', 'devflow/kanban-adapter', 'config',
                'workflow', 'identity', 'kanban', 'land', 'auto-run',
                'auto-review', 'chime', 'cron'):
    matches = [p for p in paths if p.as_posix().endswith('/spools/' + package + '/src')]
    assert len(matches) == 1, (package, matches)
    revision = subprocess.check_output(['git', '-C', str(matches[0]), 'rev-parse', 'HEAD']).decode().strip()
    assert revision == sys.argv[2], (package, revision)
print('All package sources resolve from one published revision')
PY
cat > "$ws/smoke.clj" <<'EOF'
(let [[root & consumers] *command-line-args*]
  (load-file (str root "/spools/config/test/ct/spools/codethread/shared_landing_consumer_smoke.clj"))
  (apply (resolve 'ct.spools.codethread.shared-landing-consumer-smoke/-main)
         root root consumers))
EOF
clojure -Srepro -M "$ws/smoke.clj" "$published" "${consumers[@]}"

# Install the same published package into disposable host configuration homes.
# Never select a real user workspace or start a live agent/Weaver.
PI_CODING_AGENT_DIR="$ws/pi" PI_OFFLINE=1 PI_TELEMETRY=0 \
  pi install "$published/spools/harnesses" --no-approve
PI_CODING_AGENT_DIR="$ws/pi" PI_OFFLINE=1 pi list > "$ws/pi-list"
rg -F "$published/spools/harnesses" "$ws/pi-list"
CODEX_HOME="$ws/codex" codex plugin marketplace add \
  "$published/spools/harnesses" --json
CODEX_HOME="$ws/codex" codex plugin add millstrand-identity@harnesses --json
CODEX_HOME="$ws/codex" codex plugin list --json > "$ws/codex-plugins"
rg -F 'millstrand-identity' "$ws/codex-plugins"
printf 'Published distribution and isolated native installation: clean %s\n' "$sha"
