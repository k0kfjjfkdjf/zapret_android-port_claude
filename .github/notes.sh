#!/bin/sh
# Release notes for version $1: its section of CHANGELOG.md, then the common notes.
set -e
v="$1"
section=$(awk -v h="## $v" '$0 == h { on = 1; next } on && /^## / { exit } on { print }' CHANGELOG.md)
if [ -n "$section" ]; then
  printf '**Что изменилось в %s**\n%s\n\n---\n\n' "$v" "$section"
fi
# The Tor module exists since 0.11.0: older releases get the notes without its paragraph.
major=$(echo "$v" | cut -d. -f1); minor=$(echo "$v" | cut -d. -f2)
if [ "$major" -eq 0 ] && [ "$minor" -lt 11 ]; then
  awk '/^\*\*Модуль Tor\*\*/ { skip = 1 } skip && /^$/ { skip = 0; next } !skip' .github/release-notes.md
else
  cat .github/release-notes.md
fi
