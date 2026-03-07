# Build and Deployment

## Overview

The app has two separate ways to run: the Makefile (local dev) and Docker
(production / prod-parity testing). They are independent — local development
does not use Docker.

## Local Development (Makefile)

The Makefile drives local dev via Maven directly on the host:

- `make run` — starts the app on port 8080 with real OAuth
- `make run-no-auth` — starts on an ephemeral port with the `dev` profile
  (mock auth, dummy OAuth creds)
- `make compile` — compiles without running, used to trigger DevTools hot reload

All targets except `compile` source `../.env` for database and OAuth
credentials. The `dev` profile overrides OAuth creds with placeholders but still
needs `DATABASE_URL` from the env file.

## Docker (Production)

Production runs on Koyeb using a Docker image. The `Dockerfile` is a multi-stage
build:

1. **Build stage** — uses `eclipse-temurin:17-jdk`, installs Maven, compiles the
   fat JAR with `mvn package`
2. **Runtime stage** — uses `eclipse-temurin:17-jre` (slim), copies only the JAR,
   exposes port 8080

Koyeb injects environment variables (DATABASE_URL, OAuth creds) at runtime.

## Running Docker Locally

`docker-compose.yml` lets you build and run the production image locally for
parity testing:

- `make docker-build` — builds the image
- `make docker-up` — builds and runs with `docker compose up`

The compose file reads `../.env` for secrets, same as the Makefile targets.

## Environment Variables

All secrets live in `../.env` (the bare repo base directory). This file is
gitignored. See `.env.example` for the required variables:

- `DATABASE_URL` — Neon PostgreSQL connection string
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` — Google OAuth2 credentials
- `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` — GitHub OAuth2 credentials

The database is hosted on Neon — there is no local PostgreSQL container.
