# Operations

## Services

| Service | Port | Holds credentials | Purpose |
| --- | --- | --- | --- |
| `frontend` | 3000 | No | Next.js dashboard and server-side proxy |
| `backend` | 8080 | Yes | Control plane: GitHub, review engine, jobs |
| `runner` | 8090 (internal only) | No | Executes approved generated tests |
| `postgres` | 5432 (internal only) | — | Domain and workflow state |
| `redis` | 6379 (internal only) | — | Sessions, installation tokens, job queues |

The runner, PostgreSQL, and Redis are intentionally not published to the host in `compose.yaml`.
Only the services that need them can reach them over Docker networks.

## Verifying a deployment

```bash
make smoke                       # against localhost
API_URL=https://api.example.com UI_URL=https://reviewforge.example.com \
  RUNNER_CONTAINER=reviewforge-runner-1 ./scripts/smoke.sh
```

The script exits non-zero on the first failed expectation, so it is safe to use as a
post-deploy gate. Its runner checks need Docker access to the runner container; they are skipped
with a `SKIP` line when the container is not reachable, and the rest of the checks still run.

## Health and readiness

```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/api/v1/capabilities
```

`/api/v1/capabilities` reports which integrations are configured — GitHub, the review model,
and the runner — without exposing any credential value. The dashboard uses it to disable
actions that would otherwise fail late.

## Scaling the workers

Every backend instance runs `JOB_WORKERS` queue consumers by default. To separate API serving
from background work, run two deployments off the same image:

```bash
# API only
JOB_WORKERS_ENABLED=false

# Workers only (any number of replicas)
JOB_WORKERS_ENABLED=true JOB_WORKERS=4
```

Claiming is atomic in Redis, so replicas never process the same job concurrently.

## Failure handling

| Symptom | Where to look | Expected behaviour |
| --- | --- | --- |
| Analysis stuck in `QUEUED` | `JOB_WORKERS_ENABLED`, Redis connectivity | Nothing is consuming the queue |
| Analysis `FAILED` with `MODEL_RATE_LIMITED` | `analysis_jobs.error_code` | Retried with backoff up to `JOB_MAX_ATTEMPTS`, then failed |
| Analysis `STALE` | `analysis_jobs.error_message` | The head commit moved; start a new review |
| Worker killed mid-run | `lease_expires_at`, `JobMaintenance` logs | Lease expires and the job is requeued or failed |
| Test run `INFRASTRUCTURE_ERROR` | `test_runs.stderr` | Snapshot, module detection, or Maven failed before tests ran |
| Test run `CANCELLED` | `test_runs.stderr` | Approval was withdrawn, execution was disabled, or the commit moved |

Queue depth and lease recovery are logged by `JobWorker` and `JobMaintenance`. The job state of
record is always the database row, not the queue payload.

## Cost controls

| Variable | Effect |
| --- | --- |
| `REVIEW_MAX_FILES` | Hard cap on files sent to the model per analysis |
| `REVIEW_MAX_FILE_BYTES` | Skips files larger than the limit, and records the omission |
| `REVIEW_MAX_CONTEXT_BYTES` | Total context budget; files beyond it are reported as omitted |
| `LLM_EFFORT` | `LOW`–`MAX`; trades cost and latency for depth |
| `LLM_MAX_OUTPUT_TOKENS` | Ceiling on generated tokens per call |
| `REVIEW_MIN_CONFIDENCE` | Server-side floor; low-confidence claims are discarded before storage |

`analysis_jobs.input_tokens` and `output_tokens` record what each analysis actually consumed.

## Runner limits

| Variable | Default | Effect |
| --- | --- | --- |
| `RUNNER_RUN_TIMEOUT_SECONDS` | 300 | Per-run wall clock; the process tree is killed on expiry |
| `RUNNER_MAX_RUN_SECONDS` | 600 | Hard ceiling the caller cannot exceed |
| `RUNNER_MAX_EXTRACTED_BYTES` | 512 MB | Refuses archives that expand beyond it |
| `RUNNER_MAX_EXTRACTED_ENTRIES` | 60000 | Refuses archives with too many entries |
| `RUNNER_MAX_OUTPUT_BYTES` | 65536 | Output is truncated to the tail, where failures appear |
| `RUNNER_MAVEN_OFFLINE` | true | Forbid dependency downloads at run time |

The runner image contains an immutable Maven repository for its approved Java/Maven dependency
set. Its root filesystem is read-only, each source workspace and Maven home lives on disposable
`tmpfs`, and its Compose network is internal, so reviewed code cannot download dependencies or
reach the public network. Rebuild the runner image when the approved dependency set changes.

## Backups and data

PostgreSQL holds everything durable; Redis holds only sessions, cached installation tokens, and
in-flight queue state, and can be flushed at the cost of signing users out and losing queued
work. Generated test sources are stored on `generated_tests.file_content`, so a report remains
readable after the runner is torn down.

## Rotating credentials

1. GitHub App private key: replace `GITHUB_APP_PRIVATE_KEY_BASE64` and restart the backend.
   Cached installation tokens in Redis expire on their own within the hour.
2. Model key: replace `GEMINI_API_KEY` and restart. In-flight analyses fail with
   `MODEL_AUTHENTICATION_FAILED` and can be re-run.
3. Session invalidation: `DEL reviewforge:session:*` in Redis signs everyone out.
