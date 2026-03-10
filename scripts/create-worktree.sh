#!/usr/bin/env bash
#
# Create a new worktree with a branch off claude.
#
# Usage: scripts/create-worktree.sh <branch-name>
#
# Creates the worktree as a peer directory to the current worktree
# (e.g., alongside claude/, main/) under the armchair root.
#
# Can be run from any directory — resolves paths from its own location.
#

set -euo pipefail

# --- Self-locate and derive paths ---
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARMCHAIR_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLAUDE_WORKTREE="$ARMCHAIR_ROOT/claude"

if [ $# -lt 1 ]; then
    echo "Usage: create-worktree.sh <branch-name>"
    exit 1
fi

BRANCH="$1"

# --- Validate branch name (alphanumeric, hyphens, underscores, dots, slashes) ---
if ! [[ "$BRANCH" =~ ^[a-zA-Z0-9._/-]+$ ]]; then
    echo "ERROR: Invalid branch name '${BRANCH}'."
    echo "Branch names may only contain alphanumeric characters, hyphens, underscores, dots, and slashes."
    exit 1
fi

# --- Validate claude worktree exists ---
if [ ! -d "$CLAUDE_WORKTREE/.git" ] && [ ! -f "$CLAUDE_WORKTREE/.git" ]; then
    echo "ERROR: Claude worktree not found at ${CLAUDE_WORKTREE}"
    echo "This script expects to live at <armchair-root>/claude/scripts/"
    exit 1
fi

# --- Validate claude branch exists ---
if ! git -C "$CLAUDE_WORKTREE" show-ref --verify --quiet "refs/heads/claude"; then
    echo "ERROR: Branch 'claude' does not exist."
    echo "Create the 'claude' branch first before creating worktrees off it."
    exit 1
fi

WORKTREE_PATH="${ARMCHAIR_ROOT}/${BRANCH}"

# --- Validate branch doesn't already exist ---
if git -C "$CLAUDE_WORKTREE" show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    echo "ERROR: Branch '${BRANCH}' already exists."
    exit 1
fi

# --- Validate worktree directory doesn't already exist ---
if [ -d "$WORKTREE_PATH" ]; then
    echo "ERROR: Directory already exists: ${WORKTREE_PATH}"
    exit 1
fi

# --- Create branch off claude and worktree in one step ---
git -C "$CLAUDE_WORKTREE" worktree add -b "$BRANCH" "$WORKTREE_PATH" claude

echo ""
echo "Created worktree at: ${WORKTREE_PATH}"
