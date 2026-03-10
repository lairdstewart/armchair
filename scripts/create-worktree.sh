#!/usr/bin/env bash
#
# Create a new worktree with a branch off claude.
#
# Usage: scripts/create-worktree.sh <branch-name>
#
# Creates the worktree as a peer directory to the current worktree
# (e.g., alongside claude/, main/) under the armchair root.
#

set -euo pipefail

BRANCH="${1:?Usage: create-worktree.sh <branch-name>}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
ARMCHAIR_ROOT="$(dirname "$REPO_ROOT")"
WORKTREE_PATH="${ARMCHAIR_ROOT}/${BRANCH}"

# Validate branch doesn't already exist
if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    echo "ERROR: Branch '${BRANCH}' already exists."
    exit 1
fi

# Validate worktree directory doesn't already exist
if [ -d "$WORKTREE_PATH" ]; then
    echo "ERROR: Directory already exists: ${WORKTREE_PATH}"
    exit 1
fi

# Create branch off claude and worktree in one step
git worktree add -b "$BRANCH" "$WORKTREE_PATH" claude

echo ""
echo "Created worktree at: ${WORKTREE_PATH}"
