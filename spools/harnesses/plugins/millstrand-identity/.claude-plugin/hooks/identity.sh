#!/usr/bin/env bash
# Bind Claude Code sessions to Millstrand identities and return the canonical
# identity instruction as SessionStart additional context.
set -u

request_timeout=3s

if ! command -v jq >/dev/null 2>&1; then
	printf '%s\n' '{"continue":true,"systemMessage":"Millstrand identity startup failed before binding; this session is unbound."}'
	exit 0
fi

failure() {
	local message=$1
	jq -cn --arg message "$message" '{continue: false, stopReason: $message, systemMessage: $message}'
}

payload=$(cat) || {
	failure "Millstrand identity startup could not read the Claude hook payload; this session is unbound."
	exit 0
}
if ! jq -e '
	(type == "object") and
	(.hook_event_name == "SessionStart") and
	(.session_id | type == "string" and length > 0) and
	(.cwd | type == "string" and length > 0)
' >/dev/null 2>&1 <<<"$payload"; then
	failure "Millstrand identity startup received an invalid SessionStart payload; this session is unbound."
	exit 0
fi

session_id=$(jq -er '.session_id' <<<"$payload")
cwd=$(jq -er '.cwd' <<<"$payload")
# Claude omits the model from some SessionStart payloads; record that honestly.
model=$(jq -er '.model // "unknown" | select(type == "string" and length > 0)' <<<"$payload") || model=unknown

# Project routing is authoritative; inherited workspace/identity hints never
# activate the adapter outside the launch project.
common_dir=$(git -C "$cwd" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
[[ "$(basename "$common_dir")" == .git ]] || exit 0
workspace="$(dirname "$common_dir")/.millstrand"
[[ -d "$workspace" ]] || exit 0
workspace=$(cd -P "$workspace" && pwd) || exit 0

strand_bin=${MILLSTRAND_CLAUDE_STRAND_BIN:-strand}
if ! command -v "$strand_bin" >/dev/null 2>&1; then
	failure "Millstrand identity startup cannot find Strand; this session is unbound."
	exit 0
fi

stderr_file=$(mktemp "${TMPDIR:-/tmp}/claude-millstrand-identity.XXXXXX") || {
	failure "Millstrand identity startup could not allocate diagnostic storage; this session is unbound."
	exit 0
}
trap 'rm -f "$stderr_file"' EXIT

# Managed launches pass only their run reference; direct sessions register an
# external run.
args=(agent native-startup claude "$session_id" --model "$model")
if [[ -n "${MILLSTRAND_RUN_REFERENCE:-}" ]]; then
	args+=(--run-reference "$MILLSTRAND_RUN_REFERENCE")
fi
response=$(env -u MILLSTRAND_AGENT_ID -u MILLSTRAND_RUN_ID -u MILLSTRAND_RUN_REFERENCE \
	-u MILLSTRAND_WORKSPACE \
	"$strand_bin" --workspace "$workspace" --cwd "$cwd" --timeout "$request_timeout" \
	"${args[@]}" 2>"$stderr_file")
status=$?
if ((status != 0)); then
	diagnostic=$(LC_ALL=C head -c 80 "$stderr_file" | tr '\n\r\t' '   ')
	failure "Millstrand identity startup is unavailable (exit $status): $diagnostic. This session is unbound."
	exit 0
fi

if ! context=$(jq -er '
	select(
		(.operation == "agent native-startup") and
		(.identity | type == "string" and length > 0) and
		(.instruction | type == "string" and length > 0)
	) | .instruction
' <<<"$response" 2>/dev/null); then
	failure "Millstrand identity startup returned an invalid response; this session is unbound."
	exit 0
fi

workspace_json=$(jq -Rnr --arg workspace "$workspace" '$workspace | @json')
context+=" Millstrand workspace: $workspace_json. Pass \`--workspace\` with that exact path on Strand commands."

jq -cn --arg context "$context" \
	'{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: $context}}'
