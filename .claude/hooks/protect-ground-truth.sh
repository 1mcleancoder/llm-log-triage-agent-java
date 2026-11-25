#!/usr/bin/env bash
input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
cmd=$(echo "$input"  | jq -r '.tool_input.command  // empty')
protected='(data/logs|answers)/|\.claude/(hooks/|settings\.json)'

# Edit / Write
if [[ -n "$path" ]] && echo "$path" | grep -Eq "$protected"; then
  echo "Blocked: $path is eval ground truth." \
       "Change the agent, not the data." >&2
  exit 2
fi

# Bash: protected path + a writing operation
if [[ -n "$cmd" ]] && echo "$cmd" | grep -Eq "$protected" \
   && echo "$cmd" | grep -Eq '(>|sed -i|tee|rm |mv |cp |truncate|dd )'; then
  echo "Blocked: shell command would modify ground truth: $cmd" >&2
  exit 2
fi
exit 0