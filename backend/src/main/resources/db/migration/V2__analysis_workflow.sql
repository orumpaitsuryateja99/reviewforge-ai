-- Milestones 3-7: queue bookkeeping, model accounting, and workflow lineage.

ALTER TABLE analysis_jobs
    ADD COLUMN attempts SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN context_file_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN input_tokens INTEGER,
    ADD COLUMN output_tokens INTEGER,
    ADD CONSTRAINT ck_analysis_attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_analysis_context_files CHECK (context_file_count >= 0);

CREATE UNIQUE INDEX uq_analysis_active_per_pull_request
    ON analysis_jobs (pull_request_id)
    WHERE status IN ('QUEUED', 'BUILDING_CONTEXT', 'ANALYZING', 'VALIDATING');

CREATE INDEX idx_analysis_lease ON analysis_jobs (lease_expires_at)
    WHERE status IN ('BUILDING_CONTEXT', 'ANALYZING', 'VALIDATING');

ALTER TABLE findings
    ADD COLUMN ordinal SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_finding_ordinal CHECK (ordinal >= 0);

CREATE TABLE rejected_findings (
    id UUID PRIMARY KEY,
    analysis_job_id UUID NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
    reason_code VARCHAR(48) NOT NULL,
    reason_detail TEXT NOT NULL,
    file_path TEXT,
    start_line INTEGER,
    end_line INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_rejected_reason CHECK (reason_code IN (
        'UNKNOWN_FILE', 'FILE_NOT_IN_DIFF', 'LINE_OUT_OF_RANGE', 'EVIDENCE_MISMATCH',
        'INVALID_ENUM', 'INVALID_CONFIDENCE', 'DUPLICATE', 'MISSING_FIELD', 'UNCHANGED_LINES'
    ))
);

CREATE INDEX idx_rejected_findings_analysis ON rejected_findings (analysis_job_id);

ALTER TABLE generated_tests
    ADD COLUMN analysis_job_id UUID REFERENCES analysis_jobs(id) ON DELETE CASCADE,
    ADD COLUMN head_sha CHAR(40),
    ADD COLUMN file_content TEXT,
    ADD COLUMN attempts SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_generated_test_head_sha CHECK (head_sha IS NULL OR head_sha ~ '^[0-9a-f]{40}$'),
    ADD CONSTRAINT ck_generated_test_attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_generated_test_content CHECK (status <> 'PROPOSED' OR file_content IS NOT NULL);

CREATE INDEX idx_generated_tests_analysis ON generated_tests (analysis_job_id);

ALTER TABLE test_runs
    ADD COLUMN attempts SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN duration_millis INTEGER,
    ADD CONSTRAINT ck_test_run_attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_test_run_duration CHECK (duration_millis IS NULL OR duration_millis >= 0);
