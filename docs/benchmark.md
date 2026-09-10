# Benchmarking

ReviewForge measures two different things, and keeps them separate on purpose.

## 1. Validation benchmark (runs on every build)

`ValidationBenchmarkTest` replays labelled model proposals from
`backend/src/test/resources/benchmark/validation-cases.json` through the real
`FindingValidator` and reports precision and recall. Each proposal records the outcome
ReviewForge must produce — accept, or reject with a specific reason — for a fixed commit
context. It needs no provider credential and no network, so a regression in the trust layer
fails the build rather than showing up as a bad review months later.

```bash
make benchmark
# or
cd backend && mvn -Dtest=ValidationBenchmarkTest test
```

Output:

```text
ReviewForge validation benchmark
  proposals                    10
  supported (expected accept)  2
  accepted                     2
  precision                    1.000
  recall                       1.000
```

The build requires precision and recall of 1.0: the validator is deterministic, so anything
lower means a real rule changed. Add a case whenever a new failure mode is discovered — a
hallucinated path, a drifting line number, evidence that does not match the source — and the
guarantee grows with the fixture set.

### Adding a case

Each case supplies one file (path, patch, and head source lines) and a list of proposals. A
proposal sets `expected` to `ACCEPT` or `REJECT`, and a rejection may name the
`expectedReason` that must appear among the discarded claims:

```json
{
  "label": "evidence quoted from a line the model never saw",
  "expected": "REJECT",
  "expectedReason": "EVIDENCE_MISMATCH",
  "category": "CORRECTNESS",
  "severity": "MEDIUM",
  "title": "Quantity is never validated",
  "explanation": "Claims a guard that does not exist.",
  "startLine": 5,
  "endLine": 6,
  "evidence": "if (quantity <= 0) throw new IllegalArgumentException();",
  "failureScenario": "Negative quantities pass through.",
  "suggestedFix": "Validate quantity.",
  "confidence": 0.8
}
```

`filePathOverride` points a proposal at a file the analysis never supplied, which is how the
`UNKNOWN_FILE` path is exercised.

## 2. Live review benchmark (manual, costs money)

Model quality has to be measured against real pull requests, which means real API calls. Run
it deliberately, not in CI:

1. Pick pull requests with known outcomes — ideally ones where a defect was later fixed in a
   follow-up commit. Record the repository, PR number, and the commit that fixed it.
2. Point a local stack at your GitHub App and set `GEMINI_API_KEY`.
3. For each case, run a review from the dashboard and record: findings accepted, findings
   discarded (and why), whether the known defect was reported, and the input/output token
   counts stored on the analysis row.
4. Score detection rate (known defects reported) and noise rate (accepted findings that a
   reviewer judges unhelpful). The discarded-claim list is part of the result: a high discard
   rate with good detection means the validator is doing its job.

Useful SQL for step 3, once a run is complete:

```sql
SELECT a.id, a.head_sha, a.status, a.llm_model, a.input_tokens, a.output_tokens,
       (SELECT COUNT(*) FROM findings f WHERE f.analysis_job_id = a.id) AS accepted,
       (SELECT COUNT(*) FROM rejected_findings r WHERE r.analysis_job_id = a.id) AS discarded
FROM analysis_jobs a
ORDER BY a.created_at DESC
LIMIT 20;
```

Tune `REVIEW_MIN_CONFIDENCE`, `REVIEW_MAX_FINDINGS`, and `LLM_EFFORT` against those numbers.
Raising the confidence floor trades detection for precision; `LLM_EFFORT` trades cost and
latency for depth.
