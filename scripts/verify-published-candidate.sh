#!/bin/sh
set -eu

expected_branch=${1:?expected branch is required}
test "$#" -eq 1

test -z "$(git status --porcelain=v1 --untracked-files=all)"
test "$(git branch --show-current)" = "$expected_branch"
test "$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}')" = "origin/$expected_branch"
git fetch --no-tags origin "+refs/heads/$expected_branch:refs/remotes/origin/$expected_branch"
test "$(git rev-parse HEAD)" = "$(git rev-parse "refs/remotes/origin/$expected_branch")"

# This entrypoint owns the suite lock; make quality and its children do not.
exec flock -w 180 /tmp/millstrand-test.lock make quality
