"use client";

import { useEffect, useState } from "react";
import { EmptyState } from "@/components/diff-view";
import { api, duration, message } from "@/lib/api";
import type { PullRequestDetail, ReviewReport } from "@/lib/types";

/** Stable projection of the newest analysis: what was examined, found, proposed, and proven. */
export function ReportPanel({ pullRequest }: { pullRequest: PullRequestDetail }) {
  const [report, setReport] = useState<ReviewReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // The parent keys this panel by pull request, so a new pull request mounts a fresh report.
  useEffect(() => {
    let active = true;
    api<{ id: string }[]>(`/api/platform/pull-requests/${pullRequest.id}/analyses`)
      .then(async (history) => {
        if (!active) return;
        if (history.length === 0) {
          setLoading(false);
          return;
        }
        const loaded = await api<ReviewReport>(`/api/platform/analyses/${history[0].id}/report`);
        if (active) setReport(loaded);
      })
      .catch((caught: unknown) => active && setError(message(caught)))
      .finally(() => active && setLoading(false));

    return () => {
      active = false;
    };
  }, [pullRequest.id]);

  if (loading) return <p className="panel-note">Building the report…</p>;
  if (error) {
    return (
      <div className="error-banner">
        <strong>Report unavailable</strong>
        <span>{error}</span>
      </div>
    );
  }
  if (!report) {
    return (
      <EmptyState
        title="No review to report on"
        copy="Run a review from the Findings tab; the report follows the analysis that produced it."
      />
    );
  }

  const runs = report.testRuns;
  const passed = runs.filter((run) => run.status === "PASSED").length;

  return (
    <div className="report">
      <header>
        <span className="section-label">REVIEW REPORT</span>
        <h2>
          {report.repositoryFullName} #{report.pullRequestNumber}
        </h2>
        <p>
          {report.pullRequestTitle} · commit <code>{report.analysis.headSha.slice(0, 12)}</code> ·{" "}
          {report.analysis.status}
          {report.analysis.stale ? " · superseded by a newer commit" : ""}
        </p>
      </header>

      <div className="report-grid">
        {Object.entries(report.findingsBySeverity).map(([severity, count]) => (
          <div className="report-tile" key={severity}>
            <span className={`severity-chip ${severity.toLowerCase()}`}>{severity}</span>
            <strong>{count}</strong>
          </div>
        ))}
        <div className="report-tile">
          <span className="tile-label">Files in context</span>
          <strong>{report.analysis.contextFileCount}</strong>
        </div>
        <div className="report-tile">
          <span className="tile-label">Claims discarded</span>
          <strong>{report.rejectedFindings.length}</strong>
        </div>
        <div className="report-tile">
          <span className="tile-label">Tests proposed</span>
          <strong>{report.generatedTests.length}</strong>
        </div>
        <div className="report-tile">
          <span className="tile-label">Runs passed</span>
          <strong>
            {passed}/{runs.length}
          </strong>
        </div>
      </div>

      {report.generatedTests.length > 0 && (
        <section className="report-section">
          <h3>Generated tests</h3>
          <ul className="report-list">
            {report.generatedTests.map((test) => (
              <li key={test.id}>
                <span className={`status-chip ${test.status.toLowerCase()}`}>{test.status}</span>
                <code>{test.targetFilePath}</code>
              </li>
            ))}
          </ul>
        </section>
      )}

      {runs.length > 0 && (
        <section className="report-section">
          <h3>Isolated runs</h3>
          <ul className="report-list">
            {runs.map((run) => (
              <li key={run.id}>
                <span className={`status-chip ${run.status.toLowerCase()}`}>{run.status}</span>
                <span>
                  {run.testsPassed ?? 0}/{run.testsRun ?? 0} passed · {run.commandProfile} ·{" "}
                  {duration(run.durationMillis)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <footer className="report-footer">
        Generated {new Date(report.generatedAt).toLocaleString()} from the stored analysis; nothing here is
        recomputed at read time.
      </footer>
    </div>
  );
}
