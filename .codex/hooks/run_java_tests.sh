#!/bin/zsh

hook_input=$(cat)
patch_command=$(printf '%s' "$hook_input" | jq -r '.tool_input.command // ""')

if ! printf '%s\n' "$patch_command" | rg -q '^\*\*\* (Add|Update|Delete) File: .+\.java$'; then
  exit 0
fi

repo_root=$(git rev-parse --show-toplevel) || exit 0
test_log=$(mktemp /private/tmp/kakao-java-edit-test.XXXXXX)
trap 'rm -f "$test_log"' EXIT

cd "$repo_root" || exit 0
if ./gradlew test -q >"$test_log" 2>&1; then
  exit 0
fi

tail -n 80 "$test_log" >&2
exit 2
