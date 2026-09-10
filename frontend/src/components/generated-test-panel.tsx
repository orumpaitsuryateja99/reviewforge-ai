"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { PatchPreview } from "@/components/diff-view";
import { api, duration, message } from "@/lib/api";
import { RUN_TERMINAL } from "@/lib/types";
import type { Capabilities, Finding, GeneratedTest, JobAccepted, Repository, TestRun } from "@/lib/types";

/**
 * Test generation, patch preview, approval, and isolated execution for one finding.
 * Nothing here writes to the repository: approval only unlocks a run in the runner.
 */
export function GeneratedTestPanel({
  finding,
  repository,
  capabilities,
  onRepositoryChange,
}: {
  finding: Finding;
  repository: Repository;
  capabilities: Capabilities;
  onRepositoryChange: (repository: Repository) => void;
}) {
  const [test, setTest] = useState<GeneratedTest | null>(null);
  const [run, setRun] = useState<TestRun | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const loadRuns = useCallback(async (generatedTestId: string) => {
    const runs = await api<TestRun[]>(`/api/platform/generated-tests/${generatedTestId}/runs`);
    if (mounted.current) setRun(runs[0] ?? null);
  }, []);

  useEffect(() => {
    let active = true;
    api<GeneratedTest[]>(`/api/platform/findings/${finding.id}/generated-tests`)
      .then((tests) => {
        if (!active) return;
        setTest(tests[0] ?? null);
        setLoaded(true);
        if (tests[0]) void loadRuns(tests[0].id);
      })
      .catch((caught: unknown) => active && setError(message(caught)))
      .finally(() => active && setLoaded(true));
    return () => {
      active = false;
    };
  }, [finding.id, loadRuns]);

  const generatingId = test?.status === "GENERATING" ? test.id : null;
  useEffect(() => {
    if (!generatingId) return;
    const timer = window.setInterval(() => {
      api<GeneratedTest>(`/api/platform/generated-tests/${generatingId}`)
        .then((next) => mounted.current && setTest(next))
        .catch((caught: unknown) => mounted.current && setError(message(caught)));
    }, 2000);
    return () => window.clearInterval(timer);
  }, [generatingId]);

  const activeRunId = run && !RUN_TERMINAL.includes(run.status) ? run.id : null;
  useEffect(() => {
    if (!activeRunId) return;
    const timer = window.setInterval(() => {
      api<TestRun>(`/api/platform/test-runs/${activeRunId}`)
        .then((next) => mounted.current && setRun(next))
        .catch((caught: unknown) => mounted.current && setError(message(caught)));
    }, 2500);
    return () => window.clearInterval(timer);
  }, [activeRunId]);

  async function act<T>(action: () => Promise<T>) {
    setBusy(true);
    setError(null);
    try {
      return await action();
    } catch (caught) {
      setError(message(caught));
      return null;
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function generate() {
    const accepted = await act(() =>
      api<JobAccepted>(`/api/platform/findings/${finding.id}/generated-tests`, { method: "POST" }),
    );
    if (!accepted) return;
    const created = await api<GeneratedTest>(`/api/platform/generated-tests/${accepted.jobId}`);
    setTest(created);
    setRun(null);
  }

  async function decide(approved: boolean) {
    if (!test) return;
    const updated = await act(() =>
      api<GeneratedTest>(`/api/platform/generated-tests/${test.id}/approval`, {
        method: "POST",
        body: JSON.stringify({ approved }),
      }),
    );
    if (updated) setTest(updated);
  }

  async function execute() {
    if (!test) return;
    const accepted = await act(() =>
      api<JobAccepted>(`/api/platform/generated-tests/${test.id}/runs`, { method: "POST" }),
    );
    if (!accepted) return;
    setRun(await api<TestRun>(`/api/platform/test-runs/${accepted.jobId}`));
  }

  async function allowExecution() {
    const updated = await act(() =>
      api<{ repositoryId: string; fullName: string; testExecutionAllowed: boolean }>(
        `/api/platform/repositories/${repository.id}/test-execution`,
        { method: "POST", body: JSON.stringify({ allowed: true }) },
      ),
    );
    if (updated) onRepositoryChange({ ...repository, testExecutionAllowed: updated.testExecutionAllowed });
  }

  if (!loaded) return <p className="panel-note">Checking for a generated test…</p>;

  return (
    <div className="generated-test">
      {error && <div className="error-banner compact"><span>{error}</span></div>}

      {!test && (
        <div className="test-actions">
          <button className="secondary-button" onClick={() => void generate()} disabled={busy || !capabilities.review.configured}>
            Generate failing test
          </button>
          <span className="availability">
            {capabilities.review.configured
              ? "The server chooses the file path and the command; the model only writes the test."
              : "Add a model provider key to enable generation."}
          </span>
        </div>
      )}

      {test && (
        <>
          <div className="test-header">
            <span className={`status-chip ${test.status.toLowerCase()}`}>{test.status}</span>
            <code>{test.targetFilePath}</code>
          </div>

          {test.status === "GENERATING" && <p className="panel-note">Writing a test against the analyzed commit…</p>}
          {test.status === "FAILED" && <p className="panel-note error">{test.errorMessage ?? "Generation failed."}</p>}
          {test.status === "STALE" && (
            <p className="panel-note">The pull request moved past the analyzed commit. Run a new review.</p>
          )}
          {test.rationale && test.status !== "GENERATING" && <p className="panel-note">{test.rationale}</p>}

          {test.unifiedDiff && <PatchPreview patch={test.unifiedDiff} label={`Patch for ${test.targetFilePath}`} />}

          <div className="test-actions">
            {test.status === "PROPOSED" && (
              <>
                <button className="primary-button compact" onClick={() => void decide(true)} disabled={busy}>
                  Approve patch
                </button>
                <button className="secondary-button" onClick={() => void decide(false)} disabled={busy}>
                  Reject
                </button>
                <span className="availability">Approval never writes to your repository.</span>
              </>
            )}

            {test.status === "APPROVED" && capabilities.testRunner && repository.testExecutionAllowed && (
              <button
                className="primary-button compact"
                onClick={() => void execute()}
                disabled={busy || Boolean(run && !RUN_TERMINAL.includes(run.status))}
              >
                Run in isolated runner
              </button>
            )}
            {test.status === "APPROVED" && capabilities.testRunner && !repository.testExecutionAllowed && (
              <>
                <button className="secondary-button" onClick={() => void allowExecution()} disabled={busy}>
                  Allow test execution for {repository.fullName}
                </button>
                <span className="availability">Execution is off by default for every repository.</span>
              </>
            )}
            {test.status === "APPROVED" && !capabilities.testRunner && (
              <span className="availability">The isolated runner is not configured on this deployment.</span>
            )}
          </div>

          {run && <RunResult run={run} />}
        </>
      )}
    </div>
  );
}

function RunResult({ run }: { run: TestRun }) {
  const output = (run.stderr?.trim() ? run.stderr : run.stdout) ?? "";
  return (
    <div className="run-result">
      <div className="run-summary">
        <span className={`status-chip ${run.status.toLowerCase()}`}>{run.status}</span>
        <span>
          {run.testsRun ?? 0} run · {run.testsPassed ?? 0} passed · {run.testsFailed ?? 0} failed ·{" "}
          {run.testsSkipped ?? 0} skipped
        </span>
        <span className="availability">
          {run.commandProfile} · exit {run.exitCode ?? "—"} · {duration(run.durationMillis)}
        </span>
      </div>
      {output && <pre className="run-output">{output.slice(-4000)}</pre>}
    </div>
  );
}
