#!/usr/bin/env bash
#
# Finish a ticket: squash-merge, remove worktree, close issue, hot-reload.
#
# Usage: scripts/finish-ticket.sh <branch-name> "<title>" ["<body>"]
#
# Issue number is extracted from the branch name (e.g., "42-some-feature" → 42).
# Can be run from any directory — auto-cds to the claude worktree.
#

set -euo pipefail

# --- Self-locate and derive paths ---
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARMCHAIR_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLAUDE_WORKTREE="$ARMCHAIR_ROOT/claude"

if [ $# -lt 2 ]; then
    echo "Usage: finish-ticket.sh <branch-name> \"<title>\" [\"<body>\"]"
    exit 1
fi

BRANCH="$1"
TITLE="$2"
BODY="${3:-}"
ISSUE="${BRANCH%%-*}"
WORKTREE_PATH="${ARMCHAIR_ROOT}/${BRANCH}"

# --- Validate claude worktree exists ---
if [ ! -d "$CLAUDE_WORKTREE/.git" ] && [ ! -f "$CLAUDE_WORKTREE/.git" ]; then
    echo "ERROR: Claude worktree not found at ${CLAUDE_WORKTREE}"
    echo "This script expects to live at <armchair-root>/claude/scripts/"
    exit 1
fi

# --- cd to claude worktree (avoids CWD-inside-removed-worktree footgun) ---
cd "$CLAUDE_WORKTREE"

# --- Verify we're on the claude branch ---
CURRENT_BRANCH="$(git branch --show-current)"
if [ "$CURRENT_BRANCH" != "claude" ]; then
    echo "ERROR: The claude worktree is on branch '${CURRENT_BRANCH}', expected 'claude'."
    echo "Fix this before running finish-ticket."
    exit 1
fi

# --- Verify the feature worktree exists ---
if [ ! -d "$WORKTREE_PATH" ]; then
    echo "ERROR: Worktree directory does not exist: ${WORKTREE_PATH}"
    echo "Has it already been removed?"
    exit 1
fi

# --- Verify the GitHub issue exists ---
if ! gh issue view "$ISSUE" --json state >/dev/null 2>&1; then
    echo "WARNING: Could not verify GitHub issue #${ISSUE}. It may not exist or gh may not be authenticated."
    echo "Continuing anyway — the close step may fail."
fi

# --- Step 1: Squash-merge with retries on lock conflict ---
echo "[1/5] Squash-merging ${BRANCH} into claude..."

MAX_RETRIES=3
RETRY=0
while true; do
    if "${SCRIPT_DIR}/squash-merge.sh" "$BRANCH" "$TITLE" "$BODY"; then
        echo "[1/5] Squash-merge succeeded."
        break
    fi

    RETRY=$((RETRY + 1))
    if [ "$RETRY" -ge "$MAX_RETRIES" ]; then
        echo "ERROR: squash-merge failed after $MAX_RETRIES attempts."
        echo "If there are merge conflicts, rebase in the feature worktree:"
        echo "  cd ${WORKTREE_PATH}"
        echo "  git rebase claude"
        echo "  # resolve conflicts, then re-run this script"
        exit 1
    fi

    echo "Retrying squash-merge in 3 seconds (attempt $((RETRY + 1))/$MAX_RETRIES)..."
    sleep 3
done

# --- Step 2: Remove the feature worktree ---
echo "[2/5] Removing worktree at ${WORKTREE_PATH}..."
git worktree remove "$WORKTREE_PATH"
echo "[2/5] Removed worktree."

# --- Step 3: Delete the local branch ---
echo "[3/5] Deleting branch ${BRANCH}..."
git branch -D "$BRANCH"
echo "[3/5] Deleted branch."

# --- Step 4: Close the GitHub issue ---
echo "[4/5] Closing GitHub issue #${ISSUE}..."
gh issue edit "$ISSUE" --remove-label "in-progress" 2>/dev/null || true
if gh issue close "$ISSUE"; then
    echo "[4/5] Closed issue #${ISSUE}."
else
    echo "WARNING: Failed to close issue #${ISSUE}. You may need to close it manually."
fi

# --- Step 5: Compile for DevTools hot-reload ---
echo "[5/5] Compiling for hot-reload..."
make compile
echo "[5/5] Compiled."

echo ""
echo "Done. Ticket #${ISSUE} is finished."
