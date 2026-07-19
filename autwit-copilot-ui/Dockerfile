# syntax=docker/dockerfile:1

# The Vite dev server, not a production bundle.
#
# This image exists so `docker compose up` gives a working UI against the API, which is
# how the app is actually driven during integration work. A production build would mean
# nginx and a separate CORS story for SSE; the dev server proxies /api same-origin, which
# is what EventSource needs and what the host workflow already uses.
#
# BUILD CONTEXT IS THE REPO ROOT, not this directory. The `predev` script regenerates
# src/api/generated/schema.d.ts from ../docs/openapi.yaml, so the spec has to be in the
# context — the client is generated from the contract rather than hand-written, and a
# build that could not see docs/ would silently use a stale checked-in schema.
FROM node:24-alpine
WORKDIR /repo

# Manifests first, so npm ci is cached independently of source edits.
COPY autwit-copilot-ui/package.json autwit-copilot-ui/package-lock.json* ./autwit-copilot-ui/
RUN cd autwit-copilot-ui && npm ci

# The contract, then the app.
COPY docs ./docs
COPY autwit-copilot-ui ./autwit-copilot-ui

WORKDIR /repo/autwit-copilot-ui
EXPOSE 5173
CMD ["npm", "run", "dev"]
