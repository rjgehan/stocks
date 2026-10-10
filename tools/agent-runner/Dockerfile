# syntax=docker/dockerfile:1.7
# stocks agent runner (ARCHITECTURE §20.5). Built by release.yml for linux/amd64 and linux/arm64 and pushed to the
# app's own GHCR package with an `agent-runner-` tag prefix (ghcr.io/<owner>/stocks:agent-runner-latest).
#
#   docker build -t stocks-agent-runner tools/agent-runner
#
# The JavaScript output and the production dependencies are platform-independent (no native modules, no install
# scripts), so both build stages run natively on the build host; only the small final stage is per platform.

ARG NODE_IMAGE=node:22.23.2-alpine3.24

# ---------------------------------------------------------------------------------------------- build (tsc)
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

# ---------------------------------------------------------------------------------------------- production deps
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS deps
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund && npm cache clean --force

# ---------------------------------------------------------------------------------------------- runtime
FROM ${NODE_IMAGE}
LABEL org.opencontainers.image.title="stocks-agent-runner" \
      org.opencontainers.image.description="stocks agent runner (Claude and ChatGPT league bots)" \
      org.opencontainers.image.source="https://github.com/rjgehan/stocks"
ENV NODE_ENV=production \
    AGENT_DATA_DIR=/data
WORKDIR /app
# Least-changing layers first.
COPY --from=deps /src/node_modules ./node_modules
COPY package.json ./
COPY prompts ./prompts
COPY --from=build /src/dist ./dist
# /data is the agent-data bind mount (notes, spend, run logs); the image's `node` user is uid/gid 1000.
RUN mkdir -p /data && chown node:node /data
USER node
# The runner handles SIGTERM itself (cancels in-flight model requests, writes the run logs, exits).
STOPSIGNAL SIGTERM
ENTRYPOINT ["node", "dist/main.js"]
