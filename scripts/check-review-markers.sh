#!/bin/sh
# Review remarks are left in the markdown as `> @claude …` blockquotes (see AGENT.md).
# They are working notes, not content, so none may reach main. This fails while any remain.
set -eu

# Spelled in two pieces so this script never matches itself. AGENT.md is skipped because it
# documents the form; only the blockquote spelling counts, so mentioning the marker in prose is fine.
marker='@''claude'

found=$(grep -rIn --exclude-dir=.git --exclude=AGENT.md "^> $marker" . || true)

if [ -n "$found" ]; then
    echo "Unaddressed review annotations:"
    echo "$found" | sed 's/^/  /'
    echo
    echo "Answer each one in the prose and delete the annotation; see AGENT.md."
    exit 1
fi

echo "No review annotations left."
