-- Concurrency guards: API pre-checks improve errors, while these indexes are the final arbiter.

CREATE UNIQUE INDEX uq_generated_test_active_per_finding
    ON generated_tests (finding_id)
    WHERE status IN ('GENERATING', 'PROPOSED', 'APPROVED');

CREATE UNIQUE INDEX uq_test_run_active_per_generated_test
    ON test_runs (generated_test_id)
    WHERE status IN ('QUEUED', 'PREPARING', 'RUNNING');
