# Architecture

## Shape

ReviewForge AI is a modular monolith for the trusted control plane plus a separately deployed,
credential-free runner for generated code. Package boundaries are treated as module boundaries:

```text
Browser
  |
Next.js (BFF proxy; no GitHub or LLM secrets)
  |
Spring Boot control plane
  +-- github       GitHub App auth, repositories, PRs, exact-SHA content and archives
  +-- context      Diff parsing and bounded source/test/build context
  +-- review       Provider-neutral prompts and structured model output
  +-- findings     Commit-aware evidence and line validation
  +-- generation   JUnit patch generation and approval state
  +-- analysis     Analysis lifecycle, staleness, and API projections
  +-- jobs         Redis-backed orchestration, leases, and retry policy
  +-- runner       Client for the isolated runner and test-run lifecycle
  +-- reports      Stable review report projection
  |
  +-- PostgreSQL   Durable domain and job state
  +-- Redis        Sessions, work queues, and short-lived coordination
  +-- GitHub API
  +-- LLM provider
  |
Isolated runner (separate process, image, and trust boundary)
  +-- snapshot extraction, allowlisted Maven profile, Surefire parsing
```

Controllers only translate HTTP requests and responses. Application services own workflows;
adapters own GitHub, LLM, Redis, and runner protocols; persistence repositories own database
access. Domain validation does not depend on HTTP or provider SDK types.

## Core invariants

1. Every analysis stores the selected PR `head_sha`. Findings and generated tests belong to that
   immutable analysis.
2. Refreshing a PR with a different head SHA marks earlier completed analyses `STALE`; a review
   whose commit moves mid-run ends `STALE` rather than reporting stale evidence as current.
3. Model output is untrusted. It is requested against a strict schema, and every path and line
   range is checked against files retrieved from the analyzed commit. Discarded claims are stored
   with a reason so an empty result stays explainable.
4. Repository text is data, never instructions. System policy and repository context occupy
   separate prompt sections, and repository content only ever appears inside labelled data blocks.
5. A model can propose a patch but cannot choose commands. The backend selects an allowlisted
   Maven invocation and the test file path.
6. Generated code never runs in the Spring Boot process. The runner receives a source snapshot, a
   patch, and a run policy — but no GitHub token, LLM key, database credential, or Docker socket.
7. Repository modification and PR commenting require a later, explicit approval action. Neither
   occurs automatically; approval unlocks execution only.

## Review pipeline

```text
POST /pull-requests/{id}/analyses
  -> refresh PR from GitHub, pin head_sha, insert analysis_jobs row (QUEUED)
  -> enqueue ANALYSIS on the Redis ready list
                                   |
JobWorker claims the job (ready -> processing + lease)
  -> BUILDING_CONTEXT  ContextBuilder reads changed files at head_sha, bounded by
                       file count, per-file size, and a total context budget
  -> ANALYZING         ReviewPromptFactory builds policy + data blocks; LlmClient returns
                       schema-valid ModelReview
  -> VALIDATING        FindingValidator checks path, line range, changed-line overlap,
                       verbatim evidence, enums, confidence, and duplicates
  -> COMPLETED         findings + rejected_findings written for this analysis
```

Test generation and execution follow the same shape: `TEST_GENERATION` produces a proposed patch
whose path and diff are written by the server, and `TEST_RUN` sends an approved patch plus a
repository snapshot to the runner.

## Job orchestration

Redis holds four keys: a ready list, a processing list, a lease hash, and a delayed sorted set.
Claiming moves a payload from ready to processing and records a lease timestamp, so a worker that
dies leaves recoverable work rather than a lost job. `JobMaintenance` promotes due retries and
reclaims expired leases on a fixed schedule. Failures are classified as retryable (rate limits,
upstream 5xx, transport faults) or permanent (schema, authorization, validation); retryable
failures back off exponentially up to a ceiling, and give-up marks the owning row failed with the
code and message that caused it. `reviewforge.jobs.enabled=false` leaves an instance serving the
API without consuming the queue.

## Persistence model

`V1__create_core_schema.sql` creates `app_users`, `github_installations`, `repositories`,
`pull_requests`, `analysis_jobs`, `findings`, `generated_tests`, and `test_runs`.
`V2__analysis_workflow.sql` adds queue bookkeeping (`attempts`, `lease_expires_at`), model
accounting (`input_tokens`, `output_tokens`, `context_file_count`), generated-test lineage
(`analysis_job_id`, `head_sha`, `file_content`), run timing (`duration_millis`), and the
`rejected_findings` table. A partial unique index allows only one active analysis per pull request.

Foreign keys prevent orphan workflow records. Partial and composite indexes support active-job
polling, PR lookup, finding display, and run history. Status and data-shape constraints fail closed
at the database boundary.

## Trust boundaries

| Boundary | What crosses it | What never crosses it |
| --- | --- | --- |
| Browser to Next.js | Opaque HttpOnly session cookie | GitHub tokens, model keys |
| Next.js to control plane | Proxied request plus that cookie | Any server-side credential |
| Control plane to LLM provider | System policy plus repository text in data blocks | Session material, database credentials |
| Control plane to runner | tar.gz snapshot, one test file, profile name, selector, timeout | GitHub token, model key, database credentials, Docker socket |

The runner rejects archive entries that escape its workspace, refuses links and device nodes,
caps entry count and expanded size, rebuilds the process environment from scratch, keeps the
immutable dependency cache outside the workspace, bounds captured output, and deletes the
workspace whatever the outcome. Compose additionally gives it a read-only root filesystem, a
disposable `tmpfs`, no Linux capabilities, a private network, and CPU, memory, and process limits.

## API conventions

- Base path: `/api/v1`
- JSON request/response bodies
- UUID resource identifiers
- UTC ISO-8601 timestamps
- `202 Accepted` plus a job ID for long-running work
- Polling for job status
- RFC 9457-compatible `application/problem+json` errors carrying a machine-readable `code`

The full contract is in `docs/api/openapi.yaml`; every operation there is implemented.
