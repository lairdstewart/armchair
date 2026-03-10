#!/usr/bin/env bash
#
# Finish a ticket: squash-merge, remove worktree, close issue, hot-reload.
#
# Usage: scripts/finish-ticket.sh <branch-name> <issue-number>
#
# Must be run from the claude worktree.
#

set -euo pipefail

BRANCH="${1:?Usage: finish-ticket.sh <branch-name> <issue-number>}"
ISSUE="${2:?Usage: finish-ticket.sh <branch-name> <issue-number>}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
ARMCHAIR_ROOT="$(dirname "$REPO_ROOT")"
WORKTREE_PATH="${ARMCHAIR_ROOT}/${BRANCH}"
SCRIPT_DIR="$(dirname "$0")"

# --- Step 1: Squash-merge with retries on lock conflict ---

MAX_RETRIES=3
RETRY=0
while true; do
    if "${SCRIPT_DIR}/squash-merge.sh" "$BRANCH"; then
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

git worktree remove "$WORKTREE_PATH"
echo "Removed worktree at ${WORKTREE_PATH}"

# --- Step 3: Delete the local branch ---

git branch -D "$BRANCH"
echo "Deleted branch ${BRANCH}"

# --- Step 4: Close the GitHub issue ---

gh issue close "$ISSUE"
echo "Closed issue #${ISSUE}"

# --- Step 5: Compile for DevTools hot-reload ---

make compile
echo ""
echo "Done. Ticket #${ISSUE} is finished."
