#!/usr/bin/env bash
# PreToolUse: block edits to eval ground truth
input=$(cat)                      # hook gets JSON on stdin
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

case "$path" in
  *data/logs/*|*answers/*)
    echo "Blocked: $path is eval ground truth." \
         "Change the agent, not the data." >&2
    exit 2 ;;                     # exit 2 = block; stderr goes to Claude
esac
exit 0