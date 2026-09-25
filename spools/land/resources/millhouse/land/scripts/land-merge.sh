#!/bin/sh
set -eu

pr_number=${1-}
subject=${2-}
body=${3-}
branch=${4-}

die() {
  printf '%s\n' "land merge: $*" >&2
  exit 1
}

[ -n "$pr_number" ] && [ -n "$subject" ] && [ -n "$body" ] && [ -n "$branch" ] \
  || die "usage: land-merge PR_NUMBER SUBJECT BODY BRANCH"
git check-ref-format --branch "$branch" >/dev/null 2>&1 \
  || die "invalid expected PR head branch: $branch"
[ "$branch" != main ] || die "feature branch must not be main"

# Resolve and validate the PR identity before either the OPEN path or the
# idempotent MERGED retry can report success.
state=$(gh pr view "$pr_number" --json state --jq .state) \
  || die "cannot read PR $pr_number state"
head_ref_name=$(gh pr view "$pr_number" --json headRefName --jq .headRefName) \
  || die "cannot read PR $pr_number head branch"
[ "$head_ref_name" = "$branch" ] \
  || die "PR $pr_number head is $head_ref_name; expected $branch"
base_ref_name=$(gh pr view "$pr_number" --json baseRefName --jq .baseRefName) \
  || die "cannot read PR $pr_number base branch"
[ "$base_ref_name" = main ] \
  || die "PR $pr_number base is $base_ref_name; expected main"
pr_head_oid=$(gh pr view "$pr_number" --json headRefOid --jq .headRefOid) \
  || die "cannot read PR $pr_number head commit"

case "$state" in
  OPEN|MERGED) ;;
  *) die "cannot merge PR $pr_number: state is $state; expected OPEN or MERGED" ;;
esac

current_branch=$(git branch --show-current) \
  || die "cannot read the checked-out branch"
[ "$current_branch" = "$branch" ] \
  || die "checked-out branch is $current_branch; expected $branch"

status=$(git status --porcelain=v1 --untracked-files=all) \
  || die "cannot inspect the feature worktree status"
[ -z "$status" ] || die "feature worktree is dirty:\n$status"

git fetch origin "refs/heads/main:refs/remotes/origin/main" \
  || die "cannot fetch origin/main"

head=$(git rev-parse HEAD) || die "cannot read local branch HEAD"
marker=$(git rev-parse --git-path millstrand-land-quality-head) \
  || die "cannot locate the quality marker"
[ -f "$marker" ] || die "validated quality marker is missing: $marker"
validated_head=$(cat "$marker") || die "cannot read the quality marker: $marker"
[ "$validated_head" = "$head" ] \
  || die "local HEAD $head is not the validated HEAD $validated_head"
[ "$pr_head_oid" = "$head" ] \
  || die "PR $pr_number head $pr_head_oid does not match validated HEAD $head"

if [ "$state" = MERGED ]; then
  printf '%s\n' "already merged: $pr_number at expected $branch HEAD $head into main"
  exit 0
fi

git merge-base --is-ancestor origin/main HEAD \
  || die "validated branch $branch does not incorporate fetched origin/main"
git fetch origin "refs/heads/$branch:refs/remotes/origin/$branch" \
  || die "cannot fetch origin/$branch"
remote_head=$(git rev-parse "refs/remotes/origin/$branch") \
  || die "cannot read fetched origin/$branch"
[ "$remote_head" = "$head" ] \
  || die "local HEAD $head does not match origin/$branch $remote_head"

if ! gh pr ready "$pr_number"; then
  draft=$(gh pr view "$pr_number" --json isDraft --jq .isDraft) \
    || die "cannot verify PR $pr_number draft state"
  if [ "$draft" != false ]; then
    die "failed to mark PR ready: $pr_number"
  fi
fi

gh pr merge "$pr_number" --squash --subject "$subject" --body "$body" \
  --match-head-commit "$head"

state_after_merge=$(gh pr view "$pr_number" --json state --jq .state) \
  || die "cannot verify PR $pr_number state after merge"
[ "$state_after_merge" = MERGED ] \
  || die "PR $pr_number merge returned without reaching MERGED (state: $state_after_merge)"
