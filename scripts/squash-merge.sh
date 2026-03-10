#!/usr/bin/env bash
#
# Atomic squash-merge with file locking.
#
# Usage: scripts/squash-merge.sh <branch-name>
#
# Acquires a lock so only one agent can merge at a time.
# If the merge has conflicts, aborts cleanly and exits non-zero.
# The agent should then rebase their feature branch and retry.
#
# Can be run from any directory — auto-cds to the claude worktree.
#

set -euo pipefail

# --- Self-locate and derive paths ---
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARMCHAIR_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLAUDE_WORKTREE="$ARMCHAIR_ROOT/claude"

if [ $# -lt 1 ]; then
    echo "Usage: squash-merge.sh <branch-name>"
    exit 1
fi

BRANCH="$1"

# --- Validate claude worktree exists ---
if [ ! -d "$CLAUDE_WORKTREE/.git" ] && [ ! -f "$CLAUDE_WORKTREE/.git" ]; then
    echo "ERROR: Claude worktree not found at ${CLAUDE_WORKTREE}"
    echo "This script expects to live at <armchair-root>/claude/scripts/"
    exit 1
fi

# --- cd to claude worktree so git commands work regardless of where we were invoked ---
cd "$CLAUDE_WORKTREE"

# --- Validate the target branch exists ---
if ! git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    echo "ERROR: Branch '${BRANCH}' does not exist."
    echo "Available branches:"
    git branch --list | head -20
    exit 1
fi

# --- Validate the branch has commits ahead of claude ---
AHEAD_COUNT=$(git rev-list --count "claude..${BRANCH}" 2>/dev/null || echo "0")
if [ "$AHEAD_COUNT" -eq 0 ]; then
    echo "ERROR: Branch '${BRANCH}' has no commits ahead of claude. Nothing to merge."
    exit 1
fi

LOCK_DIR="${CLAUDE_WORKTREE}/.merge-lock"
LOCK_PID_FILE="${LOCK_DIR}/pid"

cleanup() {
    rm -rf "$LOCK_DIR" 2>/dev/null || true
}

# --- Acquire lock with stale detection ---
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    # Lock exists — check if the holding process is still alive
    if [ -f "$LOCK_PID_FILE" ]; then
        LOCK_PID=$(cat "$LOCK_PID_FILE" 2>/dev/null || echo "")
        if [ -n "$LOCK_PID" ] && ! kill -0 "$LOCK_PID" 2>/dev/null; then
            echo "WARNING: Stale lock detected (PID $LOCK_PID is no longer running). Reclaiming."
            rm -rf "$LOCK_DIR"
            mkdir "$LOCK_DIR"
        else
            echo "ERROR: Another merge is in progress (PID ${LOCK_PID:-unknown}, lock at $LOCK_DIR)."
            echo "If this is stale, remove it manually: rm -rf $LOCK_DIR"
            exit 1
        fi
    else
        # Lock dir exists but no PID file — likely stale from old version
        echo "WARNING: Lock dir exists without PID file (possibly stale from old script version). Reclaiming."
        rm -rf "$LOCK_DIR"
        mkdir "$LOCK_DIR"
    fi
fi
trap cleanup EXIT

# Write our PID so other agents can detect stale locks
echo $$ > "$LOCK_PID_FILE"

# --- Ensure we're on the claude branch ---
CURRENT_BRANCH="$(git branch --show-current)"
if [ "$CURRENT_BRANCH" != "claude" ]; then
    echo "ERROR: Must be on the 'claude' branch to squash-merge."
    echo "Currently on: $CURRENT_BRANCH"
    exit 1
fi

# --- Ensure worktree is clean before merging ---
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: Working tree or index is dirty. Cannot merge."
    exit 1
fi

# --- Attempt the squash merge ---
if ! git merge --squash "$BRANCH" 2>&1; then
    echo ""
    echo "ERROR: Merge conflicts detected."
    echo "Aborting merge. Rebase your feature branch onto claude and retry:"
    echo "  cd <your-worktree>"
    echo "  git rebase claude"
    echo "  # resolve conflicts"
    echo "  cd $(pwd)"
    echo "  scripts/squash-merge.sh $BRANCH"
    git merge --abort 2>/dev/null || git reset --hard HEAD
    exit 1
fi

# --- Commit the squash ---
SUBJECT=$(git log --format='%s' "${BRANCH}" -1)
git commit -m "$SUBJECT"

echo ""
echo "Squash-merged $BRANCH into $(git branch --show-current) successfully."
