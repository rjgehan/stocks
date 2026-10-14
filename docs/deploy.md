# Deploying stocks to a CasaOS server with GitHub Actions

This guide takes the repository from your Mac to a running league at `https://stocks.<your-domain>`, served by
your CasaOS home server behind your existing Cloudflare Tunnel. After the one-time setup, **every merge to `main`
deploys itself**, and a version that does not become healthy is rolled back automatically.

Spec: ARCHITECTURE §24 (deployment) and §26 (CI). Day-2 operations: [runbook.md](runbook.md).

> **How `stocks.gehan.cloud` deploys: Watchtower (no runner).** The production server already runs Watchtower,
> so the self-hosted runner is optional and `DEPLOY_ENABLED` is left unset. Every push to `main` publishes
> `ghcr.io/rjgehan/stocks:latest` (a public package), and Watchtower restarts `stocks-app` on the new image. The
> app and agent-runner services carry `com.centurylinklabs.watchtower.enable: "true"`; the database and backup
> services carry `"false"`, so they are never restarted by Watchtower. Short path: §1 → §4 (skip the
> `gha-runner` user; use `--owner` with your own user) → §7 "without GitHub" or §7B → §8 → §11. The runner
> (§5, §6) adds a pre-deploy dump and automatic rollback, but **only on a private repository or private fork**:
> `rjgehan/stocks` is public, and a self-hosted runner on a public repository is root on the server for any pull
> request GitHub does not hold for approval. Never register one on the public repository.

```
 git push main ─► GitHub Actions (GitHub-hosted)                    your CasaOS server
                  ci.yml: backend H2 + PG, frontend, contract     ┌───────────────────────────────────────────┐
                  release.yml: multi-arch image ─► ghcr.io ◄──pull─┤ Watchtower (production; polls :latest)    │
                                                                  │   or the optional self-hosted runner      │
                  (optional) release.yml deploy job ─────────────►│   (deploy/scripts/deploy.sh)              │
                                                                  │ stocks-app :8080 ◄─ stocks-cloudflared ◄─ Cloudflare ◄─ players
                                                                  │   (stocks-edge network; no host port)     │
                                                                  │ stocks-db (PostgreSQL 18.6), stocks-backup│
                                                                  │ /DATA/AppData/stocks/ (.env, data, dumps) │
                                                                  └───────────────────────────────────────────┘
```

Nothing connects **into** your server: Watchtower polls GHCR (the runner, if you use it, polls GitHub) over
outbound HTTPS, and players arrive through the tunnel. Secrets stay in `/DATA/AppData/stocks/.env` on the server; GitHub holds no secrets for this project.

## Contents

1. [What you need](#1-what-you-need)
2. [Create the GitHub repository and push](#2-create-the-github-repository-and-push)
3. [The image on GHCR, and pulling it](#3-the-image-on-ghcr-and-pulling-it)
4. [Prepare the server](#4-prepare-the-server)
5. [Install the self-hosted runner](#5-install-the-self-hosted-runner)
6. [Repository variables](#6-repository-variables)
7. [First deploy](#7-first-deploy)
8. [Cloudflare Tunnel](#8-cloudflare-tunnel)
9. [Upgrades, releases and rollbacks](#9-upgrades-releases-and-rollbacks)
10. [Backups](#10-backups)
11. [Watchtower (no runner; used in production)](#11-watchtower-no-runner-used-in-production)
12. [The agent runner (Claude and ChatGPT bots)](#12-the-agent-runner-claude-and-chatgpt-bots)
13. [Troubleshooting](#13-troubleshooting)

## 1. What you need

- A CasaOS server, **amd64 or arm64** (the image is built for both), with Docker Engine and the Compose plugin
  (CasaOS installs both; check with `docker compose version`). Budget about 2.5 GB of RAM: the app container is
  capped at 2 GB, plus PostgreSQL.
- SSH access to it with `sudo`.
- Your Cloudflare account with the domain and the tunnel you already run.
- A GitHub account. `rjgehan/stocks` and its GHCR package are public; a private fork works too (step 3).
- Market data keys for production: an **Alpaca paper** account (Basic data plan, free; use a different Alpaca
  account than the one you develop with, because Alpaca allows one stream per key) and a **Finnhub** free key.

## 2. Create the GitHub repository and push

On your Mac, in the repository:

```sh
# with the GitHub CLI
gh repo create <owner>/stocks --private --source . --remote origin --push

# or create an empty private repository named "stocks" in the GitHub web UI, then:
git remote add origin git@github.com:<owner>/stocks.git
git push -u origin main
```

Then, in **Settings** of the repository:

- **Actions → General → Approval for running fork pull request workflows from contributors**: choose **"Require
  approval for all external contributors"** (on a public repository GitHub otherwise holds only first-time
  contributors). On a private repository the setting is "Fork pull request workflows": leave "Run workflows from
  fork pull requests" **off**.
- **Actions → General → Workflow permissions**: "Read repository contents" is enough. The workflows ask for
  `packages: write` (image push) and `packages: read` (deploy) themselves.
- **Branches → Add rule for `main`** (recommended): require a pull request and the CI checks (`Backend (H2)`,
  `Backend (embedded PostgreSQL 18.6)`, `Frontend`, `OpenAPI contract`, `Compose, scripts and workflows`,
  `Docker image ...`). On a private repository this needs GitHub Pro or Team; without it, just avoid pushing
  untested work straight to `main`.

The first push to `main` already runs **Release**: CI, then the image push to GHCR. Its deploy job is skipped
until you set `DEPLOY_ENABLED` (step 6).

## 3. The image on GHCR, and pulling it

`release.yml` pushes `ghcr.io/<owner>/stocks` (owner lowercased) for `linux/amd64` and `linux/arm64`:

| Trigger | Tags |
|---|---|
| push to `main` | `latest`, `sha-<7 hex>` |
| push of tag `v1.2.3` | `1.2.3`, `1.2`, `sha-<7 hex>` |
| Run workflow (no tag input) | `sha-<7 hex>` (plus `latest` when run on `main`) |

The agent runner (`tools/agent-runner`, §12) is pushed to the **same package** with the prefix `agent-runner-`:
`agent-runner-sha-<7 hex>` always, `agent-runner-latest` from `main`, `agent-runner-1.2.3` and `agent-runner-1.2`
for a version tag. Compose runs `${STOCKS_IMAGE}:agent-runner-${STOCKS_TAG}`, so it always matches the app's tag,
and a public `stocks` package covers it too.

The image carries SBOM and provenance attestations and OCI labels. The package is **private** at first and is
linked to the repository.

- **Deploys from the pipeline need nothing else**: the deploy job logs in with its own short-lived
  `GITHUB_TOKEN`. If its pull is denied, open the package (your profile → Packages → stocks) → Package settings →
  Manage Actions access → add the `stocks` repository with the Read role.
- **Pulls outside the pipeline** (by hand, by CasaOS, by Watchtower) need one of:
  - make the package public: Package settings → Change visibility → Public. The image holds code only, never
    secrets; or
  - log the server in with a **classic** personal access token that has only the `read:packages` scope
    (GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)). Run it as every user that
    pulls: `root` for CasaOS and Watchtower, `gha-runner` for manual `docker compose pull`:

    ```sh
    echo '<PAT>' | sudo docker login ghcr.io -u <github-user> --password-stdin
    ```

## 4. Prepare the server

Everything lives in `/DATA/AppData/stocks` (the CasaOS AppData convention):

```
/DATA/AppData/stocks/
├── .env                        your configuration and secrets (mode 600; from .env.example)
├── compose/                    the Compose project directory (the deploy job's DEPLOY_DIR)
│   ├── docker-compose.yml      copied from the repository by every deploy (previous copy: .bak)
│   ├── .deploy-state           last deploy (written by deploy.sh)
│   └── .env -> ../.env         a link, so Compose finds the configuration next to the compose file
├── scripts/pg_backup.sh        copied from the repository by every deploy
├── pgdata/                     PostgreSQL 18 data
├── backups/                    nightly and pre-deploy dumps
├── agent-data/                 the agent runner's bot notes, daily spend and run logs (owned by uid 1000, §12)
└── trader-data/                the Claude trader's agents.toml (mode 600) and each agent's state and logs
                                (owned by uid 1000; docs/agents.md §2.11)
```

The compose file sits in its own `compose/` directory on purpose. CasaOS lists every Compose project Docker runs,
and its **Uninstall** always deletes the project's directory, even when you choose to keep the data. With this
layout that removes only `compose/` and the link, never the database, the dumps or `.env` (whose
`API_KEY_PEPPER` every API key depends on). See the warning in step 7.

1. Create the user the runner will run as, and let it use Docker:

   ```sh
   sudo useradd --create-home --shell /bin/bash gha-runner
   sudo usermod -aG docker gha-runner
   sudo apt-get install -y git curl    # git lets the deploy job do a small sparse checkout
   ```

2. Copy three files from the repository to the server (the repository is private, so this is the easy way):

   ```sh
   # on your Mac
   ssh <server> mkdir -p /tmp/stocks-init
   scp .env.example deploy/scripts/init-host.sh deploy/backup/pg_backup.sh <server>:/tmp/stocks-init/
   ```

3. On the server, create the directories, the `.env` with generated secrets, and hand them to `gha-runner`:

   ```sh
   sudo bash /tmp/stocks-init/init-host.sh --from /tmp/stocks-init --owner gha-runner \
        --image ghcr.io/<owner>/stocks --url https://stocks.<your-domain>
   ```

   It never overwrites an existing `.env`; it generates `DB_PASSWORD`, `API_KEY_PEPPER` and `ENGINE_SEED`, sets
   `APP_PUBLIC_URL` and `APP_PUBLIC_HOST` from `--url`, creates `compose/` with the `.env` link, and lists what is
   still empty. Add `--casaos-link` if you will install through CasaOS Import (step 7B).

4. Fill in the rest: `sudo -u gha-runner nano /DATA/AppData/stocks/.env`

   | Variable | Value |
   |---|---|
   | `APP_PUBLIC_URL` | `https://stocks.<your-domain>`, exactly the tunnel hostname |
   | `APP_PUBLIC_HOST` | `stocks.<your-domain>` (the same host, without `https://`; the CasaOS tile opens it) |
   | `ADMIN_BOOTSTRAP_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD` | your admin login (password ≥ 10 characters); used on first boot only, clear both afterwards |
   | `ALPACA_KEY_ID`, `ALPACA_SECRET_KEY` | Alpaca paper keys |
   | `FINNHUB_TOKEN` | Finnhub key |
   | `CLOUDFLARE_TUNNEL_TOKEN` | this app's own tunnel token, see step 8 (no host port is published) |
   | everything else | keep the defaults, but keep every line (CasaOS needs them) |

   Do not put `$`, quotes or spaces in values: Docker Compose interpolates `$` inside `.env`.

## 5. Install the self-hosted runner

The deploy job runs on a GitHub Actions runner installed **on the CasaOS server**, labeled `casaos`.

**Recommended: the official runner as a systemd service.** The alternative, a runner container with the Docker
socket mounted, gives the same root-equivalent power (whoever controls `/var/run/docker.sock` controls the host)
and adds another image to trust and keep updated; the service auto-updates and survives reboots.

1. GitHub → repository → **Settings → Actions → Runners → New self-hosted runner** → Linux, and the server's
   architecture (x64 or ARM64). Keep that page open: it shows the current version and a one-hour token.
2. On the server (the version below is the current one at the time of writing; use the one GitHub shows):

   ```sh
   sudo -iu gha-runner
   mkdir -p ~/actions-runner && cd ~/actions-runner
   arch=x64   # or arm64
   curl -fsSLo runner.tar.gz \
     https://github.com/actions/runner/releases/download/v2.338.0/actions-runner-linux-${arch}-2.338.0.tar.gz
   # compare `sha256sum runner.tar.gz` with the checksum on the GitHub page, then:
   tar xzf runner.tar.gz
   ./config.sh --url https://github.com/<owner>/stocks --token <TOKEN> \
               --name casaos --labels casaos --unattended
   exit
   cd /home/gha-runner/actions-runner
   sudo ./svc.sh install gha-runner
   sudo ./svc.sh start
   ```

3. **Settings → Actions → Runners** now lists `casaos` as *Idle*, with the labels `self-hosted`, `Linux`, `X64`
   (or `ARM64`) and `casaos`.

**Security.** Treat the runner as root on the server:

- It is a **repository-level** runner for a **private** repository (or private fork) only. Never register it on a
  public repository such as `rjgehan/stocks` (production there deploys with Watchtower) or for a whole
  organization.
- Only the `deploy` job of `release.yml` targets it, and only for pushes to `main` and manual runs. `ci.yml`,
  which runs pull requests, uses GitHub-hosted runners only. But a pull request from a branch of this repository
  runs *its own* copy of the workflow files, so anyone with write access could point a job at the runner: give
  write access only to people you would give `sudo` on the server, keep fork pull request workflows off (private
  repository) or "Require approval for all external contributors" (if the repository is ever made public, remove
  the runner first), and protect `main`.
- `gha-runner` has no `sudo`; it can use Docker and write `/DATA/AppData/stocks`, nothing else.
- No inbound port or SSH key is involved: the runner keeps an outbound HTTPS long-poll to GitHub, so it works
  behind NAT and Cloudflare.
- The deploy job only gets the short-lived `GITHUB_TOKEN` (`packages: read`), logged in to a job-scoped Docker
  config that is deleted afterwards.
- Optional extra gate on GitHub Pro/Team: create an environment `production` with required reviewers and add
  `environment: production` to the deploy job.

## 6. Repository variables

**Settings → Secrets and variables → Actions → Variables → New repository variable.** No secrets are needed.

| Variable | Value | Meaning |
|---|---|---|
| `DEPLOY_ENABLED` | `true` | Master switch. Anything else skips the deploy job (images are still pushed). |
| `DEPLOY_DIR` | `/DATA/AppData/stocks/compose` (default) | The Compose project directory on the server (`docker-compose.yml` and the `.env` link) |
| `DEPLOY_SYNC_COMPOSE` | `true` (default) | Copy the repository's `docker-compose.yml` into `DEPLOY_DIR` on every deploy (the old one is kept as `.bak`). Set `false` to manage the file by hand. |
| `DEPLOY_PROFILES` | empty (default) | Extra Compose profiles: `agents` runs the league bots' agent runner (§12) and makes every deploy pull `agent-runner-<tag>` too |
| `DEPLOY_HEALTH_TIMEOUT` | `420` (default) | Seconds to wait for the app's readiness check before rolling back |
| `DEPLOY_PUBLIC_URL` | `https://stocks.<your-domain>` (optional) | After a deploy, check that the tunnel answers 200 (a warning only) |

## 7. First deploy

### 7A. Through the pipeline (recommended)

**Actions → Release → Run workflow** on `main` with the tag empty (or push any commit to `main`). The deploy job:

1. checks out `docker-compose.yml` and `deploy/`, stages the compose file and `pg_backup.sh` next to the live
   copies and validates the new compose file against `.env` (nothing live changes yet);
2. pulls `ghcr.io/<owner>/stocks:sha-<7 hex>` and remembers the running image;
3. dumps the database (`backups/stocks-<date>-predeploy-<tag>.dump`) when it is already running;
4. installs the staged files into `/DATA/AppData/stocks/compose` and `scripts/`, writes `STOCKS_TAG=<tag>` into
   `.env` and runs `docker compose -p stocks up -d`;
5. waits until `stocks-app` reports healthy (Spring readiness on port 8081, inside the container);
6. if not, prints the app logs, puts the previous image and compose file back, waits for health again and fails
   the job.

If step 1 fails (a variable missing from `.env`), or the pull or the backup fails, the job stops before
changing anything on the server.

The first boot creates the schema (Flyway) and your admin. CasaOS lists every Compose project Docker runs, so the
stack appears on the CasaOS dashboard, with the title and icon from the `x-casaos` block; its tile opens
`https://<APP_PUBLIC_HOST>/`, your tunnel hostname.

> **Warning: never click Uninstall, and never save this app's settings, in CasaOS.** Manage the stack through
> `.env` and the pipeline only. CasaOS's Uninstall deletes the app's directory even when you keep the data, and an
> Uninstall that does not keep the data deletes `/DATA/AppData/stocks` itself: the database, every dump and
> `.env`. Saving the settings makes CasaOS rewrite `docker-compose.yml` as root (mode 600) next to its own `.bak`,
> which breaks the next deploy. To stop or remove the stack, run `sudo -u gha-runner docker compose -p stocks down`
> (the data stays); `up -d` from `/DATA/AppData/stocks/compose` starts it again.

Without GitHub, the same thing by hand on the server (after step 3's login or a public package):

```sh
cd /DATA/AppData/stocks/compose && sudo -u gha-runner cp /path/to/repo/docker-compose.yml .
sudo -u gha-runner docker compose -p stocks up -d
```

### 7B. Through CasaOS Import (optional)

If you prefer to install through the CasaOS UI first:

1. Run `init-host.sh` with `--casaos-link` (step 4.3). It links `/DATA/AppData/stocks/.env` to
   `/var/lib/casaos/apps/stocks/.env`; CasaOS reads that `.env` whenever it starts the app.
2. `init-host.sh` already put `pg_backup.sh` in `/DATA/AppData/stocks/scripts/`. Set `STOCKS_TAG` in `.env` to
   `latest` or a `sha-…` tag, and make sure the server can pull the image (step 3).
3. The CasaOS import form does **not** substitute `${...}` variables (the image shows up as `${STOCKS_IMAGE`), so
   render a resolved copy from `.env` and import that instead:

   ```sh
   curl -fsSLo /tmp/casaos-compose.sh https://raw.githubusercontent.com/rjgehan/stocks/main/deploy/scripts/casaos-compose.sh
   sudo bash /tmp/casaos-compose.sh          # writes /DATA/AppData/stocks/casaos-import.yml (mode 600, has secrets)
   sudo cat /DATA/AppData/stocks/casaos-import.yml
   ```

   CasaOS → **App Store** → `+` (top right) → **Install a customized app** → **Import** → paste the whole file →
   **Submit** → **Install**. Re-run the script and re-import only when `docker-compose.yml` itself changes; image
   updates arrive through Watchtower.
4. Check what CasaOS created: `docker inspect -f '{{.State.Health.Status}}' stocks-app` must become `healthy`,
   and `docker network inspect -f '{{.Internal}}' stocks-backend` must print `true`. If not, stop it with
   `sudo docker compose -p stocks down` (not CasaOS's Uninstall, see the warning in 7A) and use 7A.

Keep `DEPLOY_DIR` at `/DATA/AppData/stocks/compose`: the next pipeline deploy takes the same Compose project
(`stocks`, same container names) over from there. Do not point `DEPLOY_DIR` at `/var/lib/casaos/apps/stocks`: CasaOS writes
that compose file as root with mode 600 and may rewrite it, so the unprivileged runner can neither read nor safely
update it.

Why the compose file looks the way it does: CasaOS Import cannot resolve `${...}`, `env_file:` or relative paths
(hence the rendered copy, absolute `/DATA/AppData/stocks/...` mounts and an explicit `environment:` list), and
re-serializes the file (hence no literal `$` anywhere). `docker compose config` leaves out services behind a
profile (the `agents` runner). CI enforces these rules (`make compose-check`).

### After the first boot

1. Open `https://stocks.<your-domain>` (after step 8) and log in with the bootstrap admin.
2. Clear `ADMIN_BOOTSTRAP_USERNAME` and `ADMIN_BOOTSTRAP_PASSWORD` in `.env`; they are applied at the next deploy
   or with `docker compose -p stocks up -d` in `/DATA/AppData/stocks/compose`.
3. Check the admin System page: market-data probe, jobs, engine status.

## 8. Cloudflare Tunnel

One hostname carries everything: the SPA, `/api`, `/mcp` and the WebSocket `/ws`. Pick where your `cloudflared`
runs:

| cloudflared runs… | Tunnel service URL | `.env` |
|---|---|---|
| **as the `stocks-cloudflared` sidecar in this compose file** (default; one tunnel per app, like the owner's other CasaOS apps) | `http://stocks-app:8080` | `CLOUDFLARE_TUNNEL_TOKEN=<this app's tunnel token>` |
| your own cloudflared container (e.g. the CasaOS cloudflared app), **joined to the `stocks-edge` network** (below) | `http://stocks-app:8080` | leave `CLOUDFLARE_TUNNEL_TOKEN` empty and stop `stocks-cloudflared` |

The app publishes **no host port**, so `http://localhost:8080` or `http://<server-lan-ip>:8080` never work: every
route goes over the `stocks-edge` network.

**The sidecar (default).** Zero Trust → **Networks → Tunnels → Create a tunnel → Cloudflared**, name it `stocks`,
and copy the token from the install command it shows (the long string after `--token`). Put it in `.env` as
`CLOUDFLARE_TUNNEL_TOKEN`. On the tunnel's **Public hostname** tab add `stocks.<your-domain>`, type **HTTP**,
URL `stocks-app:8080`. The `stocks-cloudflared` container joins `stocks-edge`, so nothing is published on the host.
If you use one of the other rows instead, leave the token empty and stop `stocks-cloudflared`.

**Joining `stocks-edge`.** The stack creates the Docker network `stocks-edge`. Quick test:
`docker network connect stocks-edge <cloudflared-container>`. That is lost when the cloudflared container is
recreated; to make it permanent, add the network to cloudflared's compose file (CasaOS: the app's settings →
compose/YAML view):

```yaml
services:
  cloudflared:
    networks: [default, stocks-edge]
networks:
  stocks-edge:
    external: true
```

**Tunnel route.**

- Remotely managed tunnel (token; what the CasaOS cloudflared app usually runs): Cloudflare **Zero Trust →
  Networks → Tunnels → your tunnel → Public hostnames → Add**: subdomain `stocks`, your domain, type **HTTP**, URL
  from the table (e.g. `stocks-app:8080`). Under *Additional application settings → Connection*: connect timeout
  `10s`, keep-alive timeout `90s`. Leave "HTTP Host Header" empty; there are no TLS settings because the origin
  speaks HTTP.
- Locally managed tunnel: see [`deploy/cloudflared/config.example.yml`](../deploy/cloudflared/config.example.yml).

**Zone settings** (ARCHITECTURE §24.5):

- **Network → WebSockets: on** (the default). STOMP heartbeats every 10 s keep `/ws` under Cloudflare's ~100 s idle
  limit.
- Use a named tunnel with a stable hostname, never a Quick Tunnel.
- **Caching → Cache Rules**: *bypass* `/api/*`, `/ws`, `/mcp`, `/v3/*`, `/swagger-ui/*`, `/oauth2/*`,
  `/.well-known/*`; *cache* `/assets/*`. Never cache `/sw.js` or `/index.html` (Phase 2 PWA: a cached service worker
  keeps installed apps on an old version; the default rules leave both alone). **Speed → Optimization**: Rocket
  Loader **off**.
- **Server-Sent Events** (Phase 2, `GET /api/v1/stream`, ARCHITECTURE §19.3): covered by the `/api/*` bypass. The
  app sends `Cache-Control: no-store, no-transform` and `X-Accel-Buffering: no` and a heartbeat every 15 s, so the
  tunnel neither buffers nor idles the stream out.
- **Security → Bots**: AI clients calling `/mcp` from vendor clouds cannot solve challenges. On the Free plan,
  Bot Fight Mode cannot be skipped per path, so turn **Bot Fight Mode off** for the zone (or, on Pro, use Super Bot
  Fight Mode with a Skip rule for `/api/*`, `/mcp`, `/oauth2/*`, `/.well-known/*`). No WAF challenge rules on those
  paths.
- **AI Crawl Control**: set the "Agent" category to **Allow** on the app hostname.
- **OAuth for the claude.ai and ChatGPT connectors** (Phase 2, ARCHITECTURE §17.9): Anthropic's cloud
  (`160.79.104.0/21`) and OpenAI's fetch `/.well-known/oauth-protected-resource/mcp`,
  `/.well-known/oauth-authorization-server` and `/oauth2/jwks`, and call `/oauth2/register`, `/oauth2/token` and
  `/oauth2/revoke` server to server, with 10 s (token refresh: 30 s) timeouts. So `/oauth2/*` and `/.well-known/*`
  get the same treatment as `/mcp`: **no** challenge (Bot Fight Mode off, no WAF managed challenge, no "Under
  Attack" mode, no rate-limit rule that challenges), **no** Cloudflare Access application, **no** cache (the bypass
  rule above; the app sends its own `Cache-Control`). `/oauth2/authorize` and the SPA page `/oauth/consent` are
  ordinary browser pages (the human signs in there); never put Access in front of them either, or the connector's
  sign-in tab ends on the Access login. Do not add response transforms that rewrite `Location` headers.
- **Cloudflare Access for admin only** (free up to 50 users, with MFA): an Access application with an allow
  policy for you in front of `/admin*` and `/api/v1/admin/*`. Never put Access in front of `/api/*` or `/mcp`.
- The league's own bots (Phase 1 agent runner) call `http://app:8080/mcp` inside Docker, so they work even if
  these settings are wrong; the settings matter for the documented Claude API and OpenAI Responses recipes.

**Proxy headers.** The app trusts `X-Forwarded-Proto` and `CF-Connecting-IP` only from addresses matching
`TRUSTED_PROXIES` (default: loopback, the Docker bridges and private LAN ranges). That default is safe because no
host port is published: only containers on `stocks-edge` (cloudflared, the agent runner) can reach the app
(ARCHITECTURE §24.3). `APP_PUBLIC_URL` must be the exact `https://` hostname.

**Check it** from outside your network (a phone on mobile data, or a cloud VM):

```sh
curl -i https://stocks.<your-domain>/api/v1/me     # 401 with a JSON problem body, never a challenge page
curl -i https://stocks.<your-domain>/mcp            # 401 with WWW-Authenticate: Bearer ..., resource_metadata="..."
curl -s https://stocks.<your-domain>/.well-known/oauth-protected-resource/mcp   # JSON, "resource": ".../mcp"
curl -s https://stocks.<your-domain>/.well-known/oauth-authorization-server     # JSON, "issuer": "https://stocks.<your-domain>"
```

The `issuer` and `resource` come from `APP_PUBLIC_URL`, so it must be exactly the public `https://` hostname
(no trailing path). Connector setup for claude.ai and ChatGPT: [`agents.md`](agents.md) §2.9.

**OAuth signing key.** Nothing to configure: on first boot the app generates an RSA key, stores it in the
`oauth_signing_key` table encrypted (AES-256-GCM) under a key derived from `API_KEY_PEPPER`, and reuses it after
every restart and Watchtower upgrade; database backups include it. Trade-off: anyone with both a database dump and
the pepper could mint access tokens (they could already forge API keys), and changing the pepper makes the stored
key unreadable, so a new one is generated and connectors silently refresh their tokens. To keep the key out of the
database instead, mount an RSA private key (PKCS#8 or PKCS#1 PEM, at least 2048 bits) into the `app` container and
set `OAUTH_JWK_PEM_FILE` to its path (both need an edit of the app service in `docker-compose.yml`: a read-only
volume and the environment line). With `stocks.oauth.enabled=false` the whole OAuth server is off.

**Web Push VAPID key** (Phase 2, ARCHITECTURE §19.2). Nothing to configure either: the key pair is generated on
first boot and kept in the `push_vapid_key` table (backups include it). Keep it: every subscribed device is bound to
it. To bring your own pair, pass both halves to the app (`JDK_JAVA_OPTIONS=-Dstocks.push.vapid.public-key=…
-Dstocks.push.vapid.private-key=…`, base64url). The VAPID subject is `APP_PUBLIC_URL` (Apple requires an `https:`
or `mailto:` subject).

In the browser, the dev tools' Network tab shows `/ws` with status 101 after login.

## 9. Upgrades, releases and rollbacks

- **Upgrade = merge to `main`.** CI must pass before the image is pushed and deployed. While the single instance
  restarts (it holds a PostgreSQL advisory lock, §12.9) the site is down for about a minute; orders do not fill
  during that time and are never filled retroactively. Flyway migrates the database on start.
- **Versioned releases:** `git tag v1.2.0 && git push origin v1.2.0` builds `1.2.0` and `1.2` (never `latest`,
  which only `main` moves). Deploy it with **Actions → Release → Run workflow**, tag `1.2.0`, from the `v1.2.0` tag
  (so that tag's compose file is used). Run workflow deploys only from `main` or a `v*` tag; from any other
  branch it builds and pushes the image but skips the deploy.
- **Automatic rollback:** a deploy whose app is not healthy within `DEPLOY_HEALTH_TIMEOUT` is rolled back to the
  image that was running and the job fails (exit 1). If the old version does not come back either (exit 2), see
  [runbook.md](runbook.md#failed-deploy).
- **Manual rollback:** **Actions → Release → Run workflow** on `main`, `tag` = an older `sha-…` (listed in each
  run's summary and on the package page). The deploy job pulls it and runs the same health-gated procedure, and
  changes only the image: it keeps the server's `docker-compose.yml`. It runs on its own, without waiting for a
  build in progress. **To roll back across a compose change** (a release that added a service or a variable),
  dispatch from the `v*` tag of that release instead (tag one if needed), so its compose file is installed with
  its image. On the server, without GitHub, from the runner's checkout (the server has no other checkout):

  ```sh
  cd /home/gha-runner/actions-runner/_work/stocks/stocks
  sudo -u gha-runner bash deploy/scripts/deploy.sh --tag sha-1a2b3c4
  ```

  `STOCKS_IMAGE` comes from `.env`. This needs the package to be public, or step 3's `docker login ghcr.io` run as
  `gha-runner` (the pipeline's own login lasts only for its job).
- **Database migrations are not rolled back.** Flyway is forward-only. An older image usually starts on a newer
  schema (new tables and columns are ignored), but if a release changed the schema incompatibly, restore that
  deploy's `predeploy` dump together with the older image ([runbook.md](runbook.md#restore)).
- **Compose changes** (new variables, new services) travel with the commit and are applied by the deploy that
  ships them. Add every new variable to `.env.example` and to the server's `.env` first.
- **PostgreSQL** stays pinned at `postgres:18.6-alpine`. Patch updates: change the tag in `docker-compose.yml`.
  A major version needs a dump and restore.

## 10. Backups

The `stocks-backup` container dumps the database **every day at 05:00 UTC** (`BACKUP_TIME_UTC`) and at start if
today's dump is missing, into `/DATA/AppData/stocks/backups/stocks-YYYY-MM-DD.dump` (`pg_dump -Fc`), and deletes
dumps older than `BACKUP_KEEP_DAYS` (14). Every deploy adds `stocks-YYYY-MM-DD-predeploy-<tag>.dump`. The container
turns *unhealthy* when no backup succeeded for 25 hours.

Copy `backups/` off the server too (another disk, a NAS share, or `rclone` to cloud storage): a dump on the same
disk does not survive that disk. Restore and the restore rehearsal: [runbook.md](runbook.md#restore).

## 11. Watchtower (no runner; used in production)

Watchtower polls GHCR and restarts `stocks-app` when `latest` changes. `docker-compose.yml` already labels the
services for it:

| Service | Label `com.centurylinklabs.watchtower.enable` |
|---|---|
| `app`, `agent-runner` | `"true"`: updated on every push to `main` (`latest` and `agent-runner-latest`) |
| `db`, `backup` | `"false"`: never touched (PostgreSQL upgrades are deliberate, see the runbook) |

1. Leave `DEPLOY_ENABLED` unset (or `false`). Images keep being pushed.
2. The package `ghcr.io/rjgehan/stocks` must be public (Package settings → Change visibility), or log the server
   in with `docker login ghcr.io` as root (step 3).
3. Start the stack once (7A "without GitHub", or 7B) with `STOCKS_TAG=latest` in `.env`.
4. **An existing Watchtower needs nothing else.** If it runs with label filtering (`--label-enable` /
   `WATCHTOWER_LABEL_ENABLE=true`) it picks up `stocks-app` through the label; if it watches every container, the
   `"false"` labels keep it away from the database. If you don't run one yet, the original
   `containrrr/watchtower` was archived in December 2025; use the maintained fork and pin a release you have
   reviewed:

   ```sh
   sudo docker run -d --name watchtower --restart unless-stopped \
     -v /var/run/docker.sock:/var/run/docker.sock \
     nickfedor/watchtower:<pinned-version> --label-enable --interval 300 --cleanup
   ```

   (Add `-v /root/.docker/config.json:/config.json:ro` if the package is private.)

What you give up compared with the runner: no health-gated rollback (a broken `latest` stays broken until you
push a fix or pin `STOCKS_TAG` to an older `sha-…` tag in `.env` and run `docker compose -p stocks up -d`), no
pre-deploy dump (the nightly backup still runs), up to five minutes of delay, and changes to `docker-compose.yml`
or `pg_backup.sh` are not applied automatically: re-import or copy them by hand when a release notes it.
Database migrations run automatically when the new app starts (Flyway). The Phase 2 wave A image applies V3–V8 in
one go on its first boot and needs no new `.env` entry (docs/ROADMAP.md lists the owner's follow-up checks).

## 12. The agent runner (Claude and ChatGPT bots)

The league's two AI players trade through `stocks-agent-runner` (compose profile `agents`), which calls the app at
`http://app:8080/mcp` inside Docker and the Anthropic and OpenAI APIs outbound. It holds only the two bots' `stk_…`
keys and the two LLM keys: no admin power, no database access. How it works, its logs and its costs:
[agents.md §2.5](agents.md#25-the-leagues-agent-runner-phase-1-toolsagent-runner).

1. **Create the bots** in the app (Admin → Players → **Add player** → **AI bot**, handles `claude` and `chatgpt`,
   "Issue an API key now" ticked → **Create bot**) and copy both keys.
2. **Fill in `.env`** (`/DATA/AppData/stocks/.env`): `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `STOCKS_CLAUDE_KEY`,
   `STOCKS_CHATGPT_KEY`, `CLAUDE_MODEL`, `OPENAI_MODEL`; optionally `AGENT_DAILY_USD_CAP` (1.00 per bot per ET day),
   `AGENT_SCHEDULE_ET`, `AGENT_MAX_TOOL_CALLS`, `AGENT_MAX_RUN_SECONDS` and the `AGENT_PRICE_*` overrides. A bot with
   an empty key or model is skipped with a log line, so you can start the service first and add keys later.
3. **Data directory**: the image runs as uid 1000. `init-host.sh` gives it `agent-data/`; on a host prepared before
   that, run `sudo chown -R 1000:1000 /DATA/AppData/stocks/agent-data` once.
4. **Start it**: `cd /DATA/AppData/stocks/compose && docker compose -p stocks --profile agents up -d`. Keep it
   updated with `DEPLOY_PROFILES=agents` (pipeline, §6) or Watchtower (its label follows `agent-runner-latest`).
   After editing `.env`, run the same command again.
5. **Test once**: `docker exec stocks-agent-runner node dist/main.js --once --bot claude` (real API calls; they count
   toward the daily cap). One run per bot at a time: during a scheduled run of the same bot (09:30, 12:45, 15:50 ET,
   or an extra run it booked; up to 5 minutes) it is skipped with `status=skipped_busy`. `docker logs stocks-agent-runner` shows one summary line per run.
6. **Stop it**: `docker compose -p stocks --profile agents stop agent-runner` (orders already placed stay; open
   orders keep working in the app).

CasaOS Import leaves profile services out, so on an Import install start the runner with the same command from the
`compose/` directory.

## 13. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Deploy job waits "for a runner" | The runner is offline: `sudo systemctl status 'actions.runner.*'` on the server. Its labels must include `casaos`. |
| Deploy job skipped | `DEPLOY_ENABLED` is not exactly `true`, CI failed, it was a tag push (tags do not deploy), or Run workflow was started from a branch other than `main` or a `v*` tag. |
| `cannot pull …: denied` | Package access: step 3 ("Manage Actions access"), or the tag does not exist. |
| `.env is missing` / `not writable` | Run `init-host.sh` (step 4); `.env` must belong to `gha-runner`, and `compose/.env` must be the link to it. |
| `… still uses the old layout` | The host was set up with `docker-compose.yml` directly in `/DATA/AppData/stocks`. Re-run `init-host.sh` (step 4.3): it moves the file into `compose/` and prints the one-time `up -d --force-recreate` that relabels the containers ([runbook.md](runbook.md#host-layout)). |
| `docker compose config failed` | A variable is missing from `.env`, or a `$` is in a value. Compare with `.env.example`. Nothing was changed on the server; fix `.env` and re-run the job. |
| App unhealthy, rolled back | Read the app log in the job output (or `docker logs stocks-app`). The prod profile fails fast on a missing or invalid `APP_PUBLIC_URL`, `API_KEY_PEPPER` (< 32 bytes), `ENGINE_SEED`, missing Alpaca keys in `hybrid`, or no admin and no bootstrap variables (§23.2). |
| Tunnel returns 502 | cloudflared cannot reach the URL: check the table in step 8. Test the path from that network: `docker run --rm --network stocks-edge busybox wget -qO- http://stocks-app:8080/ \| head -c 200`. |
| Login works but `/ws` fails | WebSockets off for the zone, or a cache rule on `/ws`. |
| Requests show the proxy IP, or login cookies are not kept | `APP_PUBLIC_URL` is not the https hostname, or `TRUSTED_PROXIES` does not match cloudflared's address. |
| Stack not visible in CasaOS | CasaOS shows Compose projects it can load; check `docker compose ls`. The app works regardless. |
| Bots get a Cloudflare challenge on `/mcp` | Bot Fight Mode is on, or a WAF/Access rule covers `/mcp` (step 8). |
| `stocks-agent-runner` restarts with `cannot write to /data` | `agent-data/` is not owned by uid 1000: `sudo chown -R 1000:1000 /DATA/AppData/stocks/agent-data` (§12). |
| `bot skipped: … are not set` / `no price for …` in the runner log | Fill in the named `.env` keys, or set `AGENT_PRICE_CLAUDE` / `AGENT_PRICE_CHATGPT` for a model the runner has no price for, then `docker compose -p stocks --profile agents up -d` ([agents.md §2.5](agents.md#25-the-leagues-agent-runner-phase-1-toolsagent-runner)). |
| Runner lines show `status=error` | The message in parentheses says why: a revoked or wrong `stk_…` key (401), the app not reachable, or an LLM API error after its retries (bad key, model name or billing). The run log in `agent-data/<bot>/runs/` has the details. |
