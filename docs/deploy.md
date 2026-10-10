# Deploying stocks to a CasaOS server with GitHub Actions

This guide takes the repository from your Mac to a running league at `https://stocks.<your-domain>`, served by
your CasaOS home server behind your existing Cloudflare Tunnel. After the one-time setup, **every merge to `main`
deploys itself**, and a version that does not become healthy is rolled back automatically.

Spec: ARCHITECTURE §24 (deployment) and §26 (CI). Day-2 operations: [runbook.md](runbook.md).

```
 git push main ─► GitHub Actions (GitHub-hosted)                    your CasaOS server
                  ci.yml: backend H2 + PG, frontend, contract     ┌───────────────────────────────────────────┐
                  release.yml: multi-arch image ─► ghcr.io ◄──pull─┤ self-hosted runner [self-hosted, casaos]  │
                                                                  │   deploy/scripts/deploy.sh                │
                  release.yml deploy job ──(runner polls GitHub)─►│   docker compose -p stocks up -d          │
                                                                  │ stocks-app :8080 ◄─ cloudflared ◄─ Cloudflare ◄─ players
                                                                  │ stocks-db (PostgreSQL 18.6), stocks-backup│
                                                                  │ /DATA/AppData/stocks/ (.env, data, dumps) │
                                                                  └───────────────────────────────────────────┘
```

Nothing connects **into** your server: the runner polls GitHub over outbound HTTPS, and players arrive through
the tunnel. Secrets stay in `/DATA/AppData/stocks/.env` on the server; GitHub holds no secrets for this project.

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
11. [Alternative without a runner: Watchtower](#11-alternative-without-a-runner-watchtower)
12. [Troubleshooting](#12-troubleshooting)

## 1. What you need

- A CasaOS server, **amd64 or arm64** (the image is built for both), with Docker Engine and the Compose plugin
  (CasaOS installs both; check with `docker compose version`). Budget about 2.5 GB of RAM: the app container is
  capped at 2 GB, plus PostgreSQL.
- SSH access to it with `sudo`.
- Your Cloudflare account with the domain and the tunnel you already run.
- A GitHub account. A private repository is assumed.
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

- **Actions → General → Fork pull request workflows**: leave "Run workflows from fork pull requests" **off**.
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
├── .env                  your configuration and secrets (mode 600; from .env.example)
├── docker-compose.yml    copied from the repository by every deploy
├── .deploy-state         last deploy (written by deploy.sh)
├── scripts/pg_backup.sh  copied from the repository by every deploy
├── pgdata/               PostgreSQL 18 data
├── backups/              nightly and pre-deploy dumps
└── agent-data/           bot notes and run logs (Phase 1)
```

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

   It never overwrites an existing `.env`; it generates `DB_PASSWORD`, `API_KEY_PEPPER` and `ENGINE_SEED`, and
   lists what is still empty. Add `--casaos-link` if you will install through CasaOS Import (step 7B).

4. Fill in the rest: `sudo -u gha-runner nano /DATA/AppData/stocks/.env`

   | Variable | Value |
   |---|---|
   | `APP_PUBLIC_URL` | `https://stocks.<your-domain>`, exactly the tunnel hostname |
   | `ADMIN_BOOTSTRAP_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD` | your admin login (password ≥ 10 characters); used on first boot only, clear both afterwards |
   | `ALPACA_KEY_ID`, `ALPACA_SECRET_KEY` | Alpaca paper keys |
   | `FINNHUB_TOKEN` | Finnhub key |
   | `STOCKS_BIND_IP`, `STOCKS_PORT` | where the app listens on the host, see step 8 (default `127.0.0.1:8080`) |
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

- It is a **repository-level** runner for this private repository only. Never register it on a public repository
  or for a whole organization.
- Only the `deploy` job of `release.yml` targets it, and only for pushes to `main` and manual runs. `ci.yml`,
  which runs pull requests, uses GitHub-hosted runners only. But a pull request from a branch of this repository
  runs *its own* copy of the workflow files, so anyone with write access could point a job at the runner: give
  write access only to people you would give `sudo` on the server, keep fork pull request workflows off, and
  protect `main`.
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
| `DEPLOY_DIR` | `/DATA/AppData/stocks` (default) | Directory with `docker-compose.yml` and `.env` on the server |
| `DEPLOY_SYNC_COMPOSE` | `true` (default) | Copy the repository's `docker-compose.yml` into `DEPLOY_DIR` on every deploy (the old one is kept as `.bak`). Set `false` to manage the file by hand. |
| `DEPLOY_PROFILES` | empty (default) | Extra Compose profiles; `agents` once the Phase 1 agent runner exists |
| `DEPLOY_HEALTH_TIMEOUT` | `420` (default) | Seconds to wait for the app's readiness check before rolling back |
| `DEPLOY_PUBLIC_URL` | `https://stocks.<your-domain>` (optional) | After a deploy, check that the tunnel answers 200 (a warning only) |

## 7. First deploy

### 7A. Through the pipeline (recommended)

**Actions → Release → Run workflow** on `main` with the tag empty (or push any commit to `main`). The deploy job:

1. checks out `docker-compose.yml` and `deploy/`, copies the compose file and `pg_backup.sh` into
   `/DATA/AppData/stocks`;
2. pulls `ghcr.io/<owner>/stocks:sha-<7 hex>` and remembers the running image;
3. dumps the database (`backups/stocks-<date>-predeploy-<tag>.dump`) when it is already running;
4. writes `STOCKS_TAG=<tag>` into `.env` and runs `docker compose -p stocks up -d`;
5. waits until `stocks-app` reports healthy (Spring readiness on port 8081, inside the container);
6. if not, prints the app logs, puts the previous image and compose file back, waits for health again and fails
   the job.

The first boot creates the schema (Flyway) and your admin. CasaOS lists every Compose project Docker runs, so the
stack appears on the CasaOS dashboard, with the title and icon from the `x-casaos` block. Manage it through
`.env` and the pipeline, not through CasaOS's settings dialog: the next deploy replaces `docker-compose.yml`.

Without GitHub, the same thing by hand on the server (after step 3's login or a public package):

```sh
cd /DATA/AppData/stocks && sudo -u gha-runner cp /path/to/repo/docker-compose.yml .
sudo -u gha-runner docker compose -p stocks up -d
```

### 7B. Through CasaOS Import (optional)

If you prefer to install through the CasaOS UI first:

1. Run `init-host.sh` with `--casaos-link` (step 4.3). It links `/DATA/AppData/stocks/.env` to
   `/var/lib/casaos/apps/stocks/.env`; CasaOS reads that `.env` whenever it starts the app.
2. `init-host.sh` already put `pg_backup.sh` in `/DATA/AppData/stocks/scripts/`. Set `STOCKS_TAG` in `.env` to
   `latest` or a `sha-…` tag, and make sure the server can pull the image (step 3).
3. CasaOS → **App Store → Custom Install** (the `+` at the top right, "Install a customized app") → **Import** →
   paste the whole `docker-compose.yml` → **Submit**. Values show as `$VARIABLE` placeholders; leave them, they
   come from `.env`. → **Install**.
4. Check what CasaOS created: `docker inspect -f '{{.State.Health.Status}}' stocks-app` must become `healthy`,
   and `docker network inspect -f '{{.Internal}}' stocks-backend` must print `true`. If not, remove the app in
   CasaOS (keep the data) and use 7A.

Keep `DEPLOY_DIR` at `/DATA/AppData/stocks`: the next pipeline deploy takes the same Compose project (`stocks`,
same container names) over from there. Do not point `DEPLOY_DIR` at `/var/lib/casaos/apps/stocks`: CasaOS writes
that compose file as root with mode 600 and may rewrite it, so the unprivileged runner can neither read nor safely
update it.

Why the compose file looks the way it does: CasaOS Import interpolates the file, drops `:-default` fallbacks
(hence every key in `.env`), re-serializes it (hence no literal `$` anywhere), cannot resolve `env_file:` or
relative paths (hence absolute `/DATA/AppData/stocks/...` mounts and an explicit `environment:` list), and leaves
out services behind a profile (the `agents` runner). CI enforces these rules (`make compose-check`).

### After the first boot

1. Open `https://stocks.<your-domain>` (after step 8) and log in with the bootstrap admin.
2. Clear `ADMIN_BOOTSTRAP_USERNAME` and `ADMIN_BOOTSTRAP_PASSWORD` in `.env`; they are applied at the next deploy
   or with `docker compose -p stocks up -d` in `/DATA/AppData/stocks`.
3. Check the admin System page: market-data probe, jobs, engine status.

## 8. Cloudflare Tunnel

One hostname carries everything: the SPA, `/api`, `/mcp` and the WebSocket `/ws`. Pick where your `cloudflared`
runs:

| cloudflared runs… | Tunnel service URL | `.env` |
|---|---|---|
| **as a container, joined to the `stocks-edge` network** (recommended with the CasaOS cloudflared app) | `http://stocks-app:8080` | defaults (the published port is not even used) |
| on the host network, or as a system service | `http://localhost:8080` | `STOCKS_BIND_IP=127.0.0.1` (default) |
| as a container on another network that cannot join `stocks-edge` | `http://<server-lan-ip>:8080` | `STOCKS_BIND_IP=0.0.0.0` (then any LAN device can reach port 8080 over plain HTTP) |

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
  `/.well-known/*`; *cache* `/assets/*`. **Speed → Optimization**: Rocket Loader **off**.
- **Security → Bots**: AI clients calling `/mcp` from vendor clouds cannot solve challenges. On the Free plan,
  Bot Fight Mode cannot be skipped per path, so turn **Bot Fight Mode off** for the zone (or, on Pro, use Super Bot
  Fight Mode with a Skip rule for `/api/*`, `/mcp`, `/oauth2/*`, `/.well-known/*`). No WAF challenge rules on those
  paths.
- **AI Crawl Control**: set the "Agent" category to **Allow** on the app hostname.
- **Cloudflare Access for admin only** (free up to 50 users, with MFA): an Access application with an allow
  policy for you in front of `/admin*` and `/api/v1/admin/*`. Never put Access in front of `/api/*` or `/mcp`.
- The league's own bots (Phase 1 agent runner) call `http://app:8080/mcp` inside Docker, so they work even if
  these settings are wrong; the settings matter for the documented Claude API and OpenAI Responses recipes.

**Proxy headers.** The app trusts `X-Forwarded-Proto` and `CF-Connecting-IP` only from addresses matching
`TRUSTED_PROXIES` (default: loopback, the Docker bridges and private LAN ranges), which covers all three
placements above. `APP_PUBLIC_URL` must be the exact `https://` hostname.

**Check it** from outside your network (a phone on mobile data, or a cloud VM):

```sh
curl -i https://stocks.<your-domain>/api/v1/me     # 401 with a JSON problem body, never a challenge page
curl -i https://stocks.<your-domain>/mcp            # 401 with WWW-Authenticate
```

In the browser, the dev tools' Network tab shows `/ws` with status 101 after login.

## 9. Upgrades, releases and rollbacks

- **Upgrade = merge to `main`.** CI must pass before the image is pushed and deployed. While the single instance
  restarts (it holds a PostgreSQL advisory lock, §12.9) the site is down for about a minute; orders do not fill
  during that time and are never filled retroactively. Flyway migrates the database on start.
- **Versioned releases:** `git tag v1.2.0 && git push origin v1.2.0` builds `1.2.0` and `1.2`. Deploy it with
  **Actions → Release → Run workflow**, tag `1.2.0`, from the `v1.2.0` tag (so that tag's compose file is used).
- **Automatic rollback:** a deploy whose app is not healthy within `DEPLOY_HEALTH_TIMEOUT` is rolled back to the
  image that was running and the job fails (exit 1). If the old version does not come back either (exit 2), see
  [runbook.md](runbook.md#failed-deploy).
- **Manual rollback:** **Actions → Release → Run workflow**, `tag` = an older `sha-…` (listed in each run's
  summary and on the package page). The deploy job pulls it and runs the same health-gated procedure. On the
  server, without GitHub:
  `STOCKS_IMAGE=ghcr.io/<owner>/stocks deploy/scripts/deploy.sh --tag sha-1a2b3c4` from a checkout.
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

## 11. Alternative without a runner: Watchtower

If you would rather not run a GitHub runner on the server, a Watchtower container can poll GHCR and restart
`stocks-app` when `latest` changes.

1. Set `DEPLOY_ENABLED` to `false` (or delete it). Images keep being pushed.
2. Make the package public, or `docker login ghcr.io` as root (step 3).
3. Start the stack by hand once (7A "without GitHub", or 7B) with `STOCKS_TAG=latest` in `.env`.
4. Run Watchtower, limited to the app container. The original `containrrr/watchtower` was archived in December
   2025; use the maintained fork and pin a release you have reviewed:

   ```sh
   sudo docker run -d --name watchtower --restart unless-stopped \
     -v /var/run/docker.sock:/var/run/docker.sock \
     -v /root/.docker/config.json:/config.json:ro \
     nickfedor/watchtower:<pinned-version> --interval 300 --cleanup stocks-app
   ```

   (Drop the `config.json` line if the package is public.)

What you give up: no health-gated rollback (a broken `latest` stays broken until you push a fix or pin
`STOCKS_TAG`), no pre-deploy dump, up to five minutes of delay, and changes to `docker-compose.yml` or
`pg_backup.sh` are never applied. Watchtower also has full Docker access.

## 12. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Deploy job waits "for a runner" | The runner is offline: `sudo systemctl status 'actions.runner.*'` on the server. Its labels must include `casaos`. |
| Deploy job skipped | `DEPLOY_ENABLED` is not exactly `true`, CI failed, or it was a tag push (tags do not deploy). |
| `cannot pull …: denied` | Package access: step 3 ("Manage Actions access"), or the tag does not exist. |
| `.env is missing` / `not writable` | Run `init-host.sh` (step 4); `.env` must belong to `gha-runner`. |
| `docker compose config failed` | A variable is missing from `.env`, or a `$` is in a value. Compare with `.env.example`. |
| App unhealthy, rolled back | Read the app log in the job output (or `docker logs stocks-app`). The prod profile fails fast on a missing or invalid `APP_PUBLIC_URL`, `API_KEY_PEPPER` (< 32 bytes), `ENGINE_SEED`, missing Alpaca keys in `hybrid`, or no admin and no bootstrap variables (§23.2). |
| `port is already allocated` | Another app uses 8080 on that address: change `STOCKS_PORT` (and the tunnel URL if it uses the port). |
| Tunnel returns 502 | cloudflared cannot reach the URL: check the table in step 8. Test the path from that network: `docker run --rm --network stocks-edge busybox wget -qO- http://stocks-app:8080/ \| head -c 200`. |
| Login works but `/ws` fails | WebSockets off for the zone, or a cache rule on `/ws`. |
| Requests show the proxy IP, or login cookies are not kept | `APP_PUBLIC_URL` is not the https hostname, or `TRUSTED_PROXIES` does not match cloudflared's address. |
| Stack not visible in CasaOS | CasaOS shows Compose projects it can load; check `docker compose ls`. The app works regardless. |
| Bots get a Cloudflare challenge on `/mcp` | Bot Fight Mode is on, or a WAF/Access rule covers `/mcp` (step 8). |
