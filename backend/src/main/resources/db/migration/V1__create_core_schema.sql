CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    github_user_id BIGINT NOT NULL UNIQUE,
    github_login VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE github_installations (
    id UUID PRIMARY KEY,
    installation_id BIGINT NOT NULL UNIQUE,
    account_id BIGINT NOT NULL,
    account_login VARCHAR(255) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    installed_by_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    suspended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_installation_account_type CHECK (account_type IN ('USER', 'ORGANIZATION'))
);

CREATE TABLE repositories (
    id UUID PRIMARY KEY,
    github_installation_id UUID NOT NULL REFERENCES github_installations(id) ON DELETE CASCADE,
    github_repository_id BIGINT NOT NULL UNIQUE,
    owner_login VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    full_name VARCHAR(512) NOT NULL UNIQUE,
    default_branch VARCHAR(255) NOT NULL,
    private BOOLEAN NOT NULL,
    test_execution_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_repository_owner_name UNIQUE (owner_login, name)
);

CREATE TABLE pull_requests (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    github_pull_request_id BIGINT NOT NULL,
    number INTEGER NOT NULL,
    title TEXT NOT NULL,
    author_login VARCHAR(255) NOT NULL,
    state VARCHAR(16) NOT NULL,
    base_ref VARCHAR(255) NOT NULL,
    base_sha CHAR(40) NOT NULL,
    head_ref VARCHAR(255) NOT NULL,
    head_sha CHAR(40) NOT NULL,
    github_updated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pull_request_repository_number UNIQUE (repository_id, number),
    CONSTRAINT uq_pull_request_github_id UNIQUE (repository_id, github_pull_request_id),
    CONSTRAINT ck_pull_request_number CHECK (number > 0),
    CONSTRAINT ck_pull_request_state CHECK (state IN ('OPEN', 'CLOSED', 'MERGED')),
    CONSTRAINT ck_pull_request_base_sha CHECK (base_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT ck_pull_request_head_sha CHECK (head_sha ~ '^[0-9a-f]{40}$')
);

CREATE TABLE analysis_jobs (
    id UUID PRIMARY KEY,
    pull_request_id UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    requested_by_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    head_sha CHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    progress_percent SMALLINT NOT NULL DEFAULT 0,
    llm_provider VARCHAR(64),
    llm_model VARCHAR(128),
    error_code VARCHAR(64),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_analysis_head_sha CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT ck_analysis_status CHECK (status IN ('QUEUED', 'BUILDING_CONTEXT', 'ANALYZING', 'VALIDATING', 'COMPLETED', 'FAILED', 'STALE', 'CANCELLED')),
    CONSTRAINT ck_analysis_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_analysis_completion CHECK (
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
        OR status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
    )
);

CREATE TABLE findings (
    id UUID PRIMARY KEY,
    analysis_job_id UUID NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
    category VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(300) NOT NULL,
    explanation TEXT NOT NULL,
    file_path TEXT NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    evidence TEXT NOT NULL,
    failure_scenario TEXT NOT NULL,
    suggested_fix TEXT NOT NULL,
    confidence NUMERIC(4,3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_finding_category CHECK (category IN ('CORRECTNESS', 'SECURITY', 'RELIABILITY', 'PERFORMANCE', 'CONCURRENCY', 'DATA_INTEGRITY', 'TEST_GAP')),
    CONSTRAINT ck_finding_severity CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_finding_lines CHECK (start_line > 0 AND end_line >= start_line),
    CONSTRAINT ck_finding_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_finding_path CHECK (file_path <> '' AND file_path !~ '(^|/)\.\.(/|$)' AND file_path !~ '^/')
);

CREATE TABLE generated_tests (
    id UUID PRIMARY KEY,
    finding_id UUID NOT NULL REFERENCES findings(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL,
    target_file_path TEXT NOT NULL,
    unified_diff TEXT NOT NULL,
    rationale TEXT NOT NULL,
    approved_by_user_id UUID REFERENCES app_users(id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_generated_test_status CHECK (status IN ('GENERATING', 'PROPOSED', 'APPROVED', 'REJECTED', 'FAILED', 'STALE')),
    CONSTRAINT ck_generated_test_path CHECK (target_file_path <> '' AND target_file_path !~ '(^|/)\.\.(/|$)' AND target_file_path !~ '^/'),
    CONSTRAINT ck_generated_test_approval CHECK (
        (status = 'APPROVED' AND approved_by_user_id IS NOT NULL AND approved_at IS NOT NULL)
        OR status <> 'APPROVED'
    )
);

CREATE TABLE test_runs (
    id UUID PRIMARY KEY,
    generated_test_id UUID NOT NULL REFERENCES generated_tests(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL,
    runner_job_id VARCHAR(255),
    command_profile VARCHAR(64) NOT NULL,
    exit_code INTEGER,
    tests_run INTEGER,
    tests_passed INTEGER,
    tests_failed INTEGER,
    tests_skipped INTEGER,
    stdout TEXT,
    stderr TEXT,
    timed_out BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_test_run_status CHECK (status IN ('QUEUED', 'PREPARING', 'RUNNING', 'PASSED', 'FAILED', 'TIMED_OUT', 'INFRASTRUCTURE_ERROR', 'CANCELLED')),
    CONSTRAINT ck_test_run_counts CHECK (
        COALESCE(tests_run, 0) >= 0
        AND COALESCE(tests_passed, 0) >= 0
        AND COALESCE(tests_failed, 0) >= 0
        AND COALESCE(tests_skipped, 0) >= 0
    ),
    CONSTRAINT ck_test_run_completion CHECK (
        (status IN ('PASSED', 'FAILED', 'TIMED_OUT', 'INFRASTRUCTURE_ERROR', 'CANCELLED') AND completed_at IS NOT NULL)
        OR status NOT IN ('PASSED', 'FAILED', 'TIMED_OUT', 'INFRASTRUCTURE_ERROR', 'CANCELLED')
    )
);

CREATE INDEX idx_installations_user ON github_installations(installed_by_user_id);
CREATE INDEX idx_repositories_installation ON repositories(github_installation_id);
CREATE INDEX idx_pull_requests_repository_state ON pull_requests(repository_id, state, github_updated_at DESC);
CREATE INDEX idx_analysis_pull_request_created ON analysis_jobs(pull_request_id, created_at DESC);
CREATE INDEX idx_analysis_active_status ON analysis_jobs(status, created_at) WHERE status IN ('QUEUED', 'BUILDING_CONTEXT', 'ANALYZING', 'VALIDATING');
CREATE INDEX idx_findings_analysis_severity ON findings(analysis_job_id, severity);
CREATE INDEX idx_generated_tests_finding_created ON generated_tests(finding_id, created_at DESC);
CREATE INDEX idx_test_runs_generated_test_created ON test_runs(generated_test_id, created_at DESC);
CREATE INDEX idx_test_runs_active_status ON test_runs(status, created_at) WHERE status IN ('QUEUED', 'PREPARING', 'RUNNING');

