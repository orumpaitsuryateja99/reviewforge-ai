# ReviewForge AI

ReviewForge AI is a GitHub-connected code review platform that reads a pull request at one exact
commit, reports only defects it can prove from that commit's source, generates a failing JUnit
test for a finding, and runs that test in an isolated, credential-free runner.

No repository, pull request, finding, test, or run is mocked. Every claim the model makes is
checked against files fetched from the analyzed commit before it is stored, and every claim that
fails is kept with the reason it was discarded.

## What it does

1. **Connect** a GitHub App, pick a repository and an open pull request.
2. **Review** the pull request. The analysis is pinned to the head SHA at the moment you asked;
   if the branch moves, the analysis is marked stale rather than reported as current.
3. **Read the findings.** Each one names a file, a line range the pull request actually changed,
   verbatim evidence from the source, a failure scenario, and a fix.
4. **Generate a failing test** for a finding. The model writes only the test source; the server
   picks the path, writes the patch, and chooses the command.
5. **Approve the patch.** Approval never writes to your repository — it unlocks execution.
6. **Run it** in the isolated runner: a throwaway workspace, an allowlisted Maven invocation, and
   no credentials of any kind.
7. **Read the report** — the stable projection of one analysis: findings, discarded claims,
   proposed tests, and run results.

## Repository layout

```text
backend/                 Spring Boot control-plane API
runner/                  Isolated executor for generated tests (separate trust boundary)
frontend/                Next.js dashboard
demo-repository/         Safe baseline plus two deliberately buggy PR patches
docs/architecture.md     Architecture, invariants, and trust boundaries
docs/operations.md       Running, scaling, failure handling, and limits
docs/benchmark.md        Validation benchmark and live evaluation procedure
docs/api/openapi.yaml    REST contract; every operation is implemented
compose.yaml             Local PostgreSQL, Redis, API, runner, and UI
```

## Prerequisites

- Docker with Docker Compose (recommended)
- Or Java 25+, Maven 3.9+, Node.js 22+, PostgreSQL 17, and Redis 7.4

## Run with Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

Then open:

- Dashboard: http://localhost:3000
- API health: http://localhost:8080/api/v1/health
- Capabilities: http://localhost:8080/api/v1/capabilities
- Readiness: http://localhost:8080/actuator/health/readiness

The default credentials are for local development only. Set non-default values in `.env`
anywhere outside a disposable local environment.

Compose publishes only the dashboard and API on localhost. PostgreSQL, Redis, and the runner stay
on private Docker networks, so a locally installed PostgreSQL on port 5432 does not conflict.

## Deploy the public demo on Render

[`render.yaml`](render.yaml) provisions the Next.js dashboard, Spring Boot API, PostgreSQL, and
Render Key Value in the Ohio region. It uses free plans and prompts for all GitHub and Gemini
secrets instead of storing them in source control.

1. Push this repository to GitHub.
2. In Render, choose **New → Blueprint**, connect the repository, and deploy `render.yaml`.
3. Supply the six secret values marked `sync: false` when prompted.
4. Configure the GitHub App with these production URLs:

   | GitHub App field | Production value |
   | --- | --- |
   | Homepage URL | `https://reviewforge-ai-orumpati.onrender.com` |
   | Callback URL | `https://reviewforge-ai-orumpati.onrender.com/api/platform/auth/github/callback` |
   | Setup URL | `https://reviewforge-ai-orumpati.onrender.com/api/platform/github/installations/callback` |

5. Verify `https://reviewforge-ai-orumpati.onrender.com/api/platform/health`, then sign in and
   install the GitHub App from the dashboard.

The free public demo deliberately sets `RUNNER_ENABLED=false`. Render does not offer free private
services, and publishing an unauthenticated code-execution service would violate the runner's
trust boundary. Review and test generation work on the hosted demo; run generated tests locally
with Compose. A production deployment can add the runner as a private service with at least 2 GB
RAM and set the API's `RUNNER_URL` and `RUNNER_ENABLED=true`.

## Run services directly

Start PostgreSQL and Redis, then:

```bash
cd backend
DB_URL=jdbc:postgresql://localhost:5432/reviewforge \
DB_USERNAME=reviewforge \
DB_PASSWORD=reviewforge \
GEMINI_API_KEY=your-key \
mvn spring-boot:run
```

In a second terminal, the runner (only needed to execute generated tests):

```bash
cd runner && mvn spring-boot:run
```

In a third:

```bash
cd frontend
npm install
BACKEND_INTERNAL_URL=http://localhost:8080 npm run dev
```

## Verify

```bash
make test        # backend verify + runner verify + frontend lint/typecheck/tests/build
make benchmark   # validation benchmark only
make smoke       # end-to-end checks against a running stack (make dev first)
```

| Suite | What it covers |
| --- | --- |
| `backend/` unit | Diff parsing, finding validation, patch construction, queue retry policy, review engine and generation lifecycles, runner wire contract, validation benchmark |
| `backend/` integration | Flyway schema, and the full queued workflow on real PostgreSQL and Redis (`AnalysisWorkflowIT`) |
| `runner/` | Snapshot extraction guards, command allowlist, Surefire parsing, request validation, and execution against a stub build command |
| `frontend/` | Diff rendering, API error mapping, and the findings and generated-test panels |
| `scripts/smoke.sh` | A live stack: auth gates, the UI proxy, and a real Maven run inside the isolated runner |

`mvn verify` always runs unit tests. Integration tests use Testcontainers (PostgreSQL and Redis)
and are skipped automatically when Docker is unavailable. `AnalysisWorkflowIT` drives the whole
queued workflow — enqueue, worker, context, validation, persistence, generation, approval —
against real PostgreSQL and Redis, stubbing only GitHub and the model provider.

`make smoke` runs against a stack that is already up. It checks the authentication gates, the
dashboard proxy, and the runner's refusal to accept an escaping path or an unlisted command
profile, and it compiles and executes a real Maven project inside the runner to confirm both the
passing and failing outcomes. [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs every
suite plus the smoke test on each push.

## Connect a GitHub App

The application runs without GitHub credentials, but the **Connect GitHub** button stays disabled.
To enable it:

1. In GitHub, open **Settings → Developer settings → GitHub Apps → New GitHub App**.
2. Use these local URLs:

   | GitHub App field | Value |
   | --- | --- |
   | Homepage URL | `http://localhost:3000` |
   | Callback URL | `http://localhost:8080/api/v1/auth/github/callback` |
   | Setup URL | `http://localhost:8080/api/v1/github/installations/callback` |

3. Leave **Request user authorization (OAuth) during installation** unchecked. ReviewForge performs
   its OAuth login before starting installation so it can bind the installation to the authenticated
   user.
4. Disable webhooks for this milestone.
5. Set these repository permissions to **Read-only**:

   - Contents
   - Metadata
   - Pull requests

6. Create the app, generate a private key, and put its values in `.env`:

   ```dotenv
   GITHUB_APP_ID=123456
   GITHUB_APP_SLUG=your-reviewforge-app
   GITHUB_CLIENT_ID=Iv1.example
   GITHUB_CLIENT_SECRET=github_client_secret
   GITHUB_APP_PRIVATE_KEY_BASE64=base64_encoded_pem
   ```

   On macOS/Linux, encode the downloaded PEM without newlines:

   ```bash
   base64 < your-app.private-key.pem | tr -d '\n'
   ```

7. Restart the stack with `docker compose up --build -d`, open `http://localhost:3000`, sign in,
   then install the app on the repositories you want to inspect.

OAuth user tokens, GitHub App private keys, installation tokens, and session contents stay on the
backend. The browser receives only an opaque, HttpOnly session cookie. ReviewForge validates the
returned installation against the signed-in GitHub user before storing it.

## Enable the review engine

Review and test generation need a model provider key. The engine is provider-neutral: the domain
depends on an `LlmClient` port, and `GeminiLlmClient` is the shipped adapter, built on Google's
official Gen AI Java SDK and asking Gemini for schema-valid structured output.

Create a key in [Google AI Studio](https://aistudio.google.com/app/apikey). The default
`gemini-2.5-flash` model has a free tier; consult Google's current
[Gemini API pricing and data-use table](https://ai.google.dev/gemini-api/docs/pricing) before
sending private repository content.

```dotenv
LLM_PROVIDER=gemini
GEMINI_API_KEY=your-key
LLM_MODEL=gemini-2.5-flash
LLM_EFFORT=HIGH
```

Without a key the API still runs: `/api/v1/capabilities` reports `review.configured=false`, the
dashboard disables the review actions, and any review request fails with a clear
`LLM_NOT_CONFIGURED` problem instead of a fabricated result. Provider keys stay backend-only —
they are never sent to the browser, and never to the runner.

## Enable test execution

Executing generated tests is off by default for every repository. To run one:

1. Keep the `runner` service up (Compose starts it; it is not published to the host).
2. Approve a proposed patch in the dashboard.
3. Turn on **Allow test execution** for that repository, which sets `test_execution_allowed`.

The backend then downloads a source archive at the analyzed commit, forwards it to the runner
along with the single test file and a profile name, and stores the bounded result. The runner
receives no GitHub token, no model key, no database credentials, and no Docker socket.

For a safe first end-to-end review, use the included
[`demo-repository`](demo-repository/README.md). It has a clean Java/Maven baseline and two patches
that create deliberately buggy pull requests whose missing edge cases are suitable for generated
JUnit tests.

## Configuration

| Variable | Used by | Default | Purpose |
| --- | --- | --- | --- |
| `DB_URL` | backend | `jdbc:postgresql://localhost:5432/reviewforge` | JDBC connection URL |
| `DB_USERNAME` | backend | `reviewforge` | Database user |
| `DB_PASSWORD` | backend | `reviewforge` | Database password |
| `REDIS_HOST` | backend | `localhost` | Redis host |
| `REDIS_PORT` | backend | `6379` | Redis port |
| `REDIS_TIMEOUT` | backend | `5s` | Command timeout; keep above `JOB_POLL_TIMEOUT` |
| `APP_ALLOWED_ORIGINS` | backend | `http://localhost:3000` | Comma-separated browser origins |
| `BACKEND_INTERNAL_URL` | frontend | `http://localhost:8080` | Server-side URL used by the UI proxy |
| `GITHUB_APP_ID` | backend | — | Numeric GitHub App ID |
| `GITHUB_APP_SLUG` | backend | — | App slug used by the installation URL |
| `GITHUB_CLIENT_ID` | backend | — | OAuth client ID |
| `GITHUB_CLIENT_SECRET` | backend | — | OAuth client secret |
| `GITHUB_APP_PRIVATE_KEY_BASE64` | backend | — | Base64-encoded GitHub App PEM key |
| `GITHUB_API_VERSION` | backend | `2026-03-10` | GitHub REST API version header |
| `GITHUB_OAUTH_CALLBACK_URL` | backend | local API callback | Registered OAuth callback URL |
| `GITHUB_SETUP_CALLBACK_URL` | backend | local setup callback | Registered post-install setup URL |
| `FRONTEND_URL` | backend | `http://localhost:3000` | Browser redirect after connection |
| `SESSION_COOKIE_SECURE` | backend | `false` | Set `true` behind production HTTPS |
| `LLM_PROVIDER` | backend | `gemini` | Selects the model adapter; unknown values fail at startup |
| `GEMINI_API_KEY` | backend | — | Provider credential; blank disables review |
| `LLM_MODEL` | backend | `gemini-2.5-flash` | Model id used for review and generation |
| `LLM_EFFORT` | backend | `HIGH` | `LOW`–`MAX` reasoning effort |
| `LLM_MAX_OUTPUT_TOKENS` | backend | `16000` | Output ceiling per model call |
| `REVIEW_MAX_FILES` | backend | `12` | Files sent to the model per analysis |
| `REVIEW_MAX_FILE_BYTES` | backend | `120000` | Per-file context limit |
| `REVIEW_MAX_CONTEXT_BYTES` | backend | `400000` | Total context budget per analysis |
| `REVIEW_MAX_FINDINGS` | backend | `15` | Stored findings per analysis |
| `REVIEW_MIN_CONFIDENCE` | backend | `0.55` | Confidence floor applied before storage |
| `JOB_WORKERS_ENABLED` | backend | `true` | Set `false` for API-only instances |
| `JOB_WORKERS` | backend | `2` | Queue consumers per instance |
| `JOB_MAX_ATTEMPTS` | backend | `3` | Attempts before a job is failed |
| `JOB_LEASE` | backend | `15m` | Lease before an abandoned job is reclaimed |
| `RUNNER_ENABLED` | backend | `true` | Turns the execution feature on or off |
| `RUNNER_URL` | backend | — | Internal runner URL, for example `http://runner:8090` |
| `RUNNER_RUN_TIMEOUT_SECONDS` | backend | `300` | Requested per-run timeout |
| `RUNNER_MAX_SNAPSHOT_BYTES` | backend | `157286400` | Largest source archive forwarded to the runner |
| `RUNNER_MAX_RUN_SECONDS` | runner | `600` | Hard timeout ceiling |
| `RUNNER_MAX_EXTRACTED_BYTES` | runner | `536870912` | Largest expanded snapshot |
| `RUNNER_MAX_OUTPUT_BYTES` | runner | `65536` | Captured output per stream |
| `RUNNER_MAVEN_OFFLINE` | runner | `true` | Forbid dependency downloads during a run |

## API

The contract is in [docs/api/openapi.yaml](docs/api/openapi.yaml). The review path:

```http
POST /api/v1/pull-requests/{id}/analyses      -> 202 { jobId, status, statusUrl }
GET  /api/v1/analyses/{id}                    -> status, progress, staleness
GET  /api/v1/analyses/{id}/findings           -> validated findings
GET  /api/v1/analyses/{id}/rejected-findings  -> discarded claims and why
POST /api/v1/findings/{id}/generated-tests    -> 202
POST /api/v1/generated-tests/{id}/approval    -> { approved: true }
POST /api/v1/generated-tests/{id}/runs        -> 202
GET  /api/v1/test-runs/{id}                   -> result counts and bounded output
GET  /api/v1/analyses/{id}/report             -> stable report for one analysis
```

Errors are RFC 9457 `application/problem+json` with a machine-readable `code`.

## Milestones

- [x] 1. Foundation, health checks, frontend, PostgreSQL, Flyway, Redis infrastructure
- [x] 2. GitHub App authentication and PR diff viewer
- [x] 3. Context builder and provider-neutral LLM review engine
- [x] 4. Finding validation and findings UI
- [x] 5. JUnit generation and patch preview
- [x] 6. Isolated test runner
- [x] 7. Redis-backed asynchronous jobs and failure handling
- [x] 8. Benchmark, integration tests, reports, and full documentation

See [docs/architecture.md](docs/architecture.md) for the design and trust boundaries,
[docs/operations.md](docs/operations.md) for running it, and [docs/benchmark.md](docs/benchmark.md)
for how quality is measured.
