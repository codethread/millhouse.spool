#!/bin/sh
set -eu

policy=${1-}
branch=${2-}
registration_timeout_secs=${3-120}
poll_interval_secs=${4-5}

die() {
  printf '%s\n' "PR checks gate: $*" >&2
  exit 1
}

case "$policy" in
  required|allow-empty) ;;
  *) die "policy must be required or allow-empty" ;;
esac
[ -n "$branch" ] || die "usage: pr-checks POLICY EXPECTED_BRANCH"
case "$registration_timeout_secs" in
  ''|*[!0-9]*) die "registration timeout must be a nonnegative integer" ;;
esac
case "$poll_interval_secs" in
  ''|*[!0-9]*) die "poll interval must be a nonnegative integer" ;;
esac

# Read the PR and its check rollup in one request. In particular, do not infer
# an empty rollup from `gh pr checks` stderr: GitHub uses the same failing exit
# status for both a legitimate empty policy and command failures.
query_pr() {
  metadata=$(gh pr view "$branch" \
    --json isDraft,state,baseRefName,headRefName,headRefOid,statusCheckRollup \
    --template '{{.isDraft}}{{"\n"}}{{.state}}{{"\n"}}{{.baseRefName}}{{"\n"}}{{.headRefName}}{{"\n"}}{{.headRefOid}}{{"\n"}}{{len .statusCheckRollup}}{{"\n"}}') \
    || die "cannot read PR metadata for $branch"

  is_draft=$(printf '%s\n' "$metadata" | sed -n '1p')
  state=$(printf '%s\n' "$metadata" | sed -n '2p')
  base_ref_name=$(printf '%s\n' "$metadata" | sed -n '3p')
  head_ref_name=$(printf '%s\n' "$metadata" | sed -n '4p')
  pr_head_oid=$(printf '%s\n' "$metadata" | sed -n '5p')
  check_count=$(printf '%s\n' "$metadata" | sed -n '6p')
}

validate_pr_metadata() {
  [ "$is_draft" = false ] || die "PR for $branch is draft; expected ready for review"
  [ "$state" = OPEN ] || die "PR for $branch state is $state; expected OPEN"
  [ "$base_ref_name" = main ] \
    || die "PR for $branch base is $base_ref_name; expected main"
  [ "$head_ref_name" = "$branch" ] \
    || die "PR head is $head_ref_name; expected $branch"
  [ -n "$pr_head_oid" ] || die "PR for $branch has a blank head commit"
  case "$check_count" in
    ''|*[!0-9]*) die "PR for $branch returned invalid check count: $check_count" ;;
  esac
}

query_pr
validate_pr_metadata

git check-ref-format --branch "$branch" >/dev/null 2>&1 \
  || die "invalid expected PR head branch: $branch"
[ "$branch" != main ] || die "feature branch must not be main"

current_branch=$(git branch --show-current) \
  || die "cannot read the checked-out branch"
[ "$current_branch" = "$branch" ] \
  || die "checked-out branch is $current_branch; expected $branch"
local_head=$(git rev-parse HEAD) || die "cannot read local branch HEAD"
[ "$pr_head_oid" = "$local_head" ] \
  || die "PR head $pr_head_oid does not match local HEAD $local_head"

remote_line=$(git ls-remote --exit-code origin "refs/heads/$branch") \
  || die "cannot read pushed origin/$branch"
pushed_head=$(printf '%s\n' "$remote_line" | awk 'NR == 1 { print $1 }')
[ -n "$pushed_head" ] || die "pushed origin/$branch has a blank head commit"
[ "$pushed_head" = "$local_head" ] \
  || die "pushed origin/$branch HEAD $pushed_head does not match local HEAD $local_head"
[ "$pr_head_oid" = "$pushed_head" ] \
  || die "PR head $pr_head_oid does not match pushed origin/$branch HEAD $pushed_head"

if [ "$check_count" -eq 0 ]; then
  if [ "$policy" = allow-empty ]; then
    printf '%s\n' "PR checks gate: no checks configured; allow-empty accepted exact $branch HEAD $local_head"
    exit 0
  fi
  started_at=$(date +%s) || die "cannot start required-check registration timer"
  while [ "$check_count" -eq 0 ]; do
    now=$(date +%s) || die "cannot read required-check registration timer"
    elapsed=$((now - started_at))
    if [ "$elapsed" -ge "$registration_timeout_secs" ]; then
      die "no checks registered for $branch within ${registration_timeout_secs}s; policy required"
    fi
    sleep "$poll_interval_secs"
    query_pr
    validate_pr_metadata
    [ "$pr_head_oid" = "$local_head" ] \
      || die "PR head $pr_head_oid changed while waiting; expected $local_head"
    [ "$pr_head_oid" = "$pushed_head" ] \
      || die "PR head $pr_head_oid does not match pushed origin/$branch HEAD $pushed_head"
  done
fi

# Once at least one check exists, GitHub CLI owns pending/pass/fail semantics.
# Its exit status is the gate result and is intentionally not reinterpreted.
exec gh pr checks "$branch" --watch --fail-fast
