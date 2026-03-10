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

set -euo pipefail

BRANCH="${1:?Usage: squash-merge.sh <branch-name>}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
LOCK_DIR="${REPO_ROOT}/.merge-lock"

cleanup() {
    rmdir "$LOCK_DIR" 2>/dev/null || true
}

# Acquire lock (mkdir is atomic on all filesystems)
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "ERROR: Another merge is in progress (lock exists at $LOCK_DIR)."
    echo "If this is stale, remove it manually: rmdir $LOCK_DIR"
    exit 1
fi
trap cleanup EXIT

# Ensure we're on the claude branch
CURRENT_BRANCH="$(git branch --show-current)"
if [ "$CURRENT_BRANCH" != "claude" ]; then
    echo "ERROR: Must be on the 'claude' branch to squash-merge."
    echo "Currently on: $CURRENT_BRANCH"
    exit 1
fi

# Ensure worktree is clean before merging
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "ERROR: Working tree or index is dirty. Cannot merge."
    exit 1
fi

# Attempt the squash merge
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

# Commit the squash
SUBJECT=$(git log --format='%s' "${BRANCH}" -1)
git commit -m "$SUBJECT"

echo ""
echo "Squash-merged $BRANCH into $(git branch --show-current) successfully."
