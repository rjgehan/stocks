# syntax=docker/dockerfile:1.7
# stocks Claude trader (docs/agents.md §2.11): headless Claude Code check-ins on the owner's Claude subscription, one
# agent per AI player (claude_trader.py in server mode). Built by release.yml for linux/amd64 and linux/arm64 and
# pushed to the app's own GHCR package with a `claude-trader-` tag prefix (ghcr.io/<owner>/stocks:claude-trader-latest).
#
#   docker build -t stocks-claude-trader tools/claude-trader
#
# Claude Code is pinned to an exact npm version, at least 14 days old when pinned. Its npm package resolves the native
# binary through per-platform optionalDependencies (@anthropic-ai/claude-code-linux-x64, -linux-arm64) and its
# postinstall script copies that binary over bin/claude.exe. Never install it with --omit=optional or
# --ignore-scripts: both leave the placeholder, a script that only prints "claude native binary not installed".

ARG NODE_IMAGE=node:22.23.2-bookworm-slim

FROM ${NODE_IMAGE}
ARG CLAUDE_CODE_VERSION=2.1.285
LABEL org.opencontainers.image.title="stocks-claude-trader" \
      org.opencontainers.image.description="stocks Claude trader (Claude Code check-ins, one agent per AI player)" \
      org.opencontainers.image.source="https://github.com/rjgehan/stocks"
# Python runs the trader (standard library only; bookworm's 3.11 has tomllib); tzdata backs zoneinfo. The base image
# tag pins the Debian release; its packages take security updates, so they are not pinned one by one.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 tzdata ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# The size check fails the build when the postinstall did not put the native binary in place (the placeholder is a
# few hundred bytes); it does not run the binary, which would be slow under QEMU for the other platform.
RUN npm install -g --no-audit --no-fund "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && npm cache clean --force \
    && test "$(stat -c %s "$(readlink -f "$(command -v claude)")")" -gt 1000000
ENV HOME=/home/node \
    STOCKS_TRADER_HOME=/data \
    DISABLE_AUTOUPDATER=1 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
COPY claude_trader.py agents.example.toml ./
# /data is the trader-data bind mount (agents.toml, each agent's state and logs); the image's `node` user is uid/gid
# 1000, so the host directory must belong to 1000:1000.
RUN mkdir -p /data && chown node:node /data
USER node
# serve handles SIGTERM itself: it ends the running claude sessions (SIGKILL after 5 s) and exits 0.
STOPSIGNAL SIGTERM
ENTRYPOINT ["python3", "/app/claude_trader.py"]
CMD ["serve"]
