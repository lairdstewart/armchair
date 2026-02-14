Instructions for Claude

# Project Overview

Armchair is a Spring Boot web application for managing ranked book lists. Users
can add books and rank them through pairwise comparisons using a binary search
insertion algorithm. Think "Beli for books."

## Tech Stack:

- Spring Boot 3.2.0 with Spring Web MVC
- Thymeleaf for server-side templating
- Java 17
- Maven for build management
- PostgreSQL database (Spring Data JPA with Hibernate)
- OAuth2 authentication via Google
- Spring Boot DevTools for hot reload during development
- Open Library API for book information

## Design Preference:

- Prefer server-side processing in Java over client-side JavaScript
- UI design should be minimal early-2000s. Think hacker-news

## Documentation:

See ./docs for particular notes. You are welcome to edit these and create new
ones as necessary.

- docs/curated-list-importer.md
- docs/database-schema.md
- docs/open-library-api-notes.md
- docs/open-library-api.md
- docs/testing.md

# Development Workflow

**worktrees:**

All work must take place in a new worktree specific to the current task. This
is necessary so that multiple Claude instances can run in parallel without
conflicts. To make any change to the code, create a new branch in a new worktree
before starting. Worktrees are placed in ./worktrees. When you are finished with
the task, remove the worktree.

**claude branch**

You are only to make changes to the 'claude' branch. Do not touch the main or dev
branches. Before starting a task, first merge changes from 'dev' into 'claude'
branch as the two may have gotten out of sync. Then, create your new branch off
of 'claude' into a worktree. Once finished with a task, switch to the 'claude'
worktree which will always have the 'claude' branch, squash merge the feature
branch to keep history linear, then remove the worktree.

**tickets**

'./tickets/todo' contains work items. If I give you a number, that means you should
look up that ticket number in the 'todo' and read it for instructions. Name your
new branch the same name as the ticket. When you are finished, move the ticket
from './tickets/todo' to './tickets/done'.

**validation**

As necessary, compile and run tests using maven to validate changes. Run the
server locally and view the website locally using the claude code chrome
extension. Use a random port so as not to conflict with other claude code
instances.

See './src/test/resources/' for example goodreads csv exports to use to test the
goodreads import tool.

 # Don't forget

- Do not add features or change behavior without the user's approval
- Do not change the database schema without the user's approval
- Respect .gitignore. Do not commit items in it.
- You are in charge of the code. Refactor and clean it as you go.
- Value clean, maintainable, extensible code even if it requires more effort.
