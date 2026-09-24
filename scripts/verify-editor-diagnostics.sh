#!/usr/bin/env bash
# Prove that headless clojure-lsp loads owner-exported Kondo configuration.
#
# This creates only disposable files. It neither reads nor writes a user's LSP
# configuration/cache, and it uses exact producer revisions.
set -euo pipefail

for command in clojure clojure-lsp; do
  command -v "$command" >/dev/null || {
    printf 'Required command not found: %s\n' "$command" >&2
    exit 1
  }
done

workspace=$(mktemp -d)
cache=$(mktemp -d)
config=$(mktemp -d)
cleanup() {
  rm -rf "${workspace:?}" "${cache:?}" "${config:?}"
}
trap cleanup EXIT

mkdir -p "$workspace/src" "$workspace/.clj-kondo"
cat > "$workspace/deps.edn" <<'EOF'
{:paths ["src"]
 :deps {io.millstrand/millstrand
        {:git/url "https://github.com/codethread/millstrand.git"
         :git/sha "8e220eab7de2fabe7880c6a4c71de6cd903c34bb"}
        millhouse.spools/chime
        {:git/url "https://github.com/codethread/millhouse.spool.git"
         :git/sha "bd96f5357a335bd17cd22042da1be5bd2200f807"
         :deps/root "spools/chime"}}}
EOF
cat > "$workspace/src/consumer.clj" <<'EOF'
(ns consumer
  (:require [millhouse.spools.chime :as chime]))

(chime/defrule sample-rule "Sample rule." [_] true)
(chime/defrule! sample-rule-bang "Sample rule." [_] true)
(chime/use-rule! sample-rule-rule sample-rule-bang-rule)
EOF

settings="{:cache-path \"$cache\"
           :project-specs [{:project-path \"deps.edn\"
                            :classpath-cmd [\"clojure\" \"-Srepro\" \"-Spath\"]}]}"
run_diagnostics() {
  XDG_CONFIG_HOME="$config" clojure-lsp diagnostics --raw \
    --project-root "$workspace" \
    --settings "$settings" \
    --filenames "$workspace/src/consumer.clj"
}

valid_output=$(run_diagnostics)
printf '%s\n' "$valid_output"
test "$valid_output" = 'No diagnostics found!'
test -f "$workspace/.clj-kondo/imports/io.millstrand/millstrand/config.edn"
test -f "$workspace/.clj-kondo/imports/millhouse.spools/chime/config.edn"

printf '\nmissing-editor-sentinel\n' >> "$workspace/src/consumer.clj"
set +e
sentinel_output=$(run_diagnostics 2>&1)
sentinel_status=$?
set -e
printf '%s\n' "$sentinel_output"
test "$sentinel_status" -ne 0
grep -Fq 'Unresolved symbol: missing-editor-sentinel' <<<"$sentinel_output"

printf '\nheadless editor diagnostics verification passed\n'
