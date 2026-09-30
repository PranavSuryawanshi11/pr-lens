# PR-Lens Bot (`glint`)

A webhook-driven GitHub App that automatically triages every incoming pull request:
it fetches the diff, runs cheap heuristics **and** an LLM review in parallel,
merges and ranks the findings, computes a triage tier, and posts a structured
review comment (plus optional labels, email digest, and one-click actions) so a
maintainer can triage a flood of PRs in minutes instead of hours.

> The human stays in the loop. The bot never merges or auto-closes — it leaves
> an `APPROVE`/comment and optional one-click buttons. Closing a PR is always a
> deliberate signed-action click.

- **Stack:** Spring Boot 4.0.2 (Java 21), Maven, PostgreSQL, WebFlux `WebClient`, Thymeleaf dashboard.
- **App name:** `glint` (see `spring.application.name` in `bot/src/main/resources/application.yaml`).
- **Design intent:** see [`docs/SRS.md`](docs/SRS.md) (Solution Requirements Spec).

## Features

- Verifies GitHub webhook signatures (HMAC `X-Hub-Signature-256`) on every request.
- Reacts to `pull_request` events: `opened`, `synchronize`, `reopened`.
- Dual signal engine:
  - **Heuristics** (`com.bot.bot.analysis.heuristics.*`) — secrets, commit-message style, diff shape, account age, comment/code ratio, boilerplate phrases. Runs synchronously, in parallel.
  - **LLM review** (`com.bot.bot.analysis.LLMReviewEngine`) — per-diff-chunk review via an OpenAI-compatible endpoint, with an ordered provider fallback chain.
- Per-installation **triage tier**: 🟢 GREEN / 🟡 YELLOW / 🔴 RED, plus a separate `security` flag.
- Posts a markdown review with a severity breakdown and optional inline comments.
- Applies `triage:green|yellow|red` (+ `security`) labels.
- **Email**: daily digest grouped per installation + immediate alert for RED/security PRs.
- **One-click actions** (`/action?token=…&do=…`): approve / request-changes / close, via signed single-use tokens — no auto-merge.
- **Dashboard** (`/`) listing recent PRs with action links.
- Dedupe: skips re-analysis when the PR's commit SHA is unchanged.
- Graceful shutdown, Prometheus metrics, custom health indicators.

## Quick start

1. [Set up the GitHub App and run the bot](docs/tutorial-getting-started.md) — end-to-end walkthrough.
2. [Configure](docs/howto-setup.md) providers, mail, and per-installation options.
3. [Reference: configuration](docs/reference-configuration.md) and [Reference: API & endpoints](docs/reference-api.md).
4. [How the analysis pipeline works](docs/explanation-analysis-pipeline.md).

## Repository layout

```
PR-Lens/
├── bot/                      # Spring Boot application (the bot)
│   ├── src/main/java/com/bot/bot/
│   │   ├── BotApplication.java        # entry point, .env loader
│   │   ├── webhook/                   # GitHubWebhookController, WebhookSignatureVerifier
│   │   ├── service/                   # ReviewOrchestrator (pipeline)
│   │   ├── analysis/                  # heuristics + LLMReviewEngine + SummaryGenerator
│   │   ├── llm/                       # LLMFallbackChain, OpenAiCompatibleClient
│   │   ├── github/                    # GitHubApiClient, GitHubJwtGenerator
│   │   ├── engine/                    # FindingMerger, ReviewPublisher
│   │   ├── actions/                   # TokenService (signed action tokens)
│   │   ├── web/                       # DashboardController, ActionController
│   │   ├── email/                     # MailService, DigestService, ThresholdAlertService
│   │   ├── config/                    # *Properties, ConfigService, ActionsConfig
│   │   ├── persistence/               # PrAnalysis, MaintainerConfig, repositories
│   │   └── domain/                    # Finding, ChangeChunk, PullRequestContext, TriageResult
│   ├── src/main/resources/application.yaml
│   ├── .env.example
│   └── pom.xml
└── docs/                     # standalone documentation (this set)
    ├── SRS.md                       # design spec
    ├── tutorial-getting-started.md
    ├── howto-setup.md
    ├── reference-configuration.md
    ├── reference-api.md
    └── explanation-analysis-pipeline.md
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the component map and data flow.

## One-Command Run (Zero Configuration)

You can run the entire project with a single command without configuring a GitHub App:

```bash
# Linux / macOS / Git Bash:
./run.sh

# Windows Command Prompt / PowerShell:
run.cmd
```

`run.sh` automatically:
1. Detects and configures Java 21+ (`JAVA_HOME`).
2. Generates `bot/.env` with safe zero-config defaults if missing.
3. Automatically uses local PostgreSQL, starts a Docker container, or falls back to an embedded zero-config H2 database (`data/pr_triage`).
4. Boots the Spring Boot server on `http://localhost:8080/`.

---

## Triage Any PR On Anyone's Account

You do not need to create or configure a GitHub App or install anything on anyone's account. You can triage pull requests on **any** repository (including public repositories or your own):

### 1. Via Command Line (`run.sh`):
```bash
# Triage any PR by full GitHub URL
./run.sh triage https://github.com/facebook/react/pull/28000

# Or by repository name and PR number
./run.sh triage octocat/Hello-World 12
```

### 2. Via REST API (`/api/triage`):
```bash
curl -X POST http://localhost:8080/api/triage \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/facebook/react/pull/28000"}'
```

### 3. Authentication Modes
- **Mode 1: Zero-Config / Public API (Tokenless)**
  Fetches PR diffs and metadata for ANY public repository on GitHub without any token or configuration.
- **Mode 2: Personal Access Token (PAT)**
  Set `GITHUB_TOKEN=ghp_...` in `bot/.env` or export `GITHUB_TOKEN`.
  Allows triaging private repositories, raises GitHub rate limits, and enables review comments and labels on repositories you have write access to.
- **Mode 3: GitHub App (Optional)**
  Configure `GITHUB_APP_ID`, `GITHUB_CLIENT_ID`, and `certs/github-app.pem` if you wish to run as a multi-tenant GitHub App with webhook signature validation.

---

## Traditional Build & Run

```bash
./run.sh test        # Run test suite
./run.sh build       # Package application JAR
./run.sh check       # Check environment & dependencies
```

## License

See repository license files.
