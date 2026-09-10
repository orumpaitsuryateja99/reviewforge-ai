"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { EmptyState } from "@/components/diff-view";
import { GeneratedTestPanel } from "@/components/generated-test-panel";
import { api, message, timeAgo } from "@/lib/api";
import { ANALYSIS_TERMINAL } from "@/lib/types";
import type {
  Analysis,
  Capabilities,
  Finding,
  JobAccepted,
  PullRequestDetail,
  RejectedFinding,
  Repository,
} from "@/lib/types";

/**
 * Review lifecycle for one pull request: request an analysis of the current head commit,
 * follow it while it runs, and show the findings that survived validation.
 */
export function FindingsPanel({
  pullRequest,
  repository,
  capabilities,
  onFindings,
  onRepositoryChange,
}: {
  pullRequest: PullRequestDetail;
  repository: Repository;
  capabilities: Capabilities;
  onFindings: (findings: Finding[]) => void;
  onRepositoryChange: (repository: Repository) => void;
}) {
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [findings, setFindings] = useState<Finding[]>([]);
  const [rejected, setRejected] = useState<RejectedFinding[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [showRejected, setShowRejected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const loadResults = useCallback(
    async (analysisId: string) => {
      const [accepted, discarded] = await Promise.all([
        api<Finding[]>(`/api/platform/analyses/${analysisId}/findings`),
        api<RejectedFinding[]>(`/api/platform/analyses/${analysisId}/rejected-findings`),
      ]);
      if (!mounted.current) return;
      setFindings(accepted);
      setRejected(discarded);
      onFindings(accepted);
    },
    [onFindings],
  );

  // The parent keys this panel by pull request, so mount-time state is already empty here.
  useEffect(() => {
    let active = true;
    api<Analysis[]>(`/api/platform/pull-requests/${pullRequest.id}/analyses`)
      .then((history) => {
        if (!active) return;
        const latest = history[0] ?? null;
        setAnalysis(latest);
        if (latest && latest.status === "COMPLETED") void loadResults(latest.id);
      })
      .catch((caught: unknown) => active && setError(message(caught)));

    return () => {
      active = false;
    };
  }, [pullRequest.id, loadResults]);

  const activeAnalysisId = analysis && !ANALYSIS_TERMINAL.includes(analysis.status) ? analysis.id : null;
  useEffect(() => {
    if (!activeAnalysisId) return;
    const timer = window.setInterval(() => {
      api<Analysis>(`/api/platform/analyses/${activeAnalysisId}`)
        .then((next) => {
          if (!mounted.current) return;
          setAnalysis(next);
          if (next.status === "COMPLETED") void loadResults(next.id);
        })
        .catch((caught: unknown) => mounted.current && setError(message(caught)));
    }, 2000);
    return () => window.clearInterval(timer);
  }, [activeAnalysisId, loadResults]);

  async function startAnalysis() {
    setBusy(true);
    setError(null);
    try {
      const accepted = await api<JobAccepted>(
        `/api/platform/pull-requests/${pullRequest.id}/analyses`,
        { method: "POST" },
      );
      const created = await api<Analysis>(`/api/platform/analyses/${accepted.jobId}`);
      setAnalysis(created);
      setFindings([]);
      setRejected([]);
      onFindings([]);
    } catch (caught) {
      setError(message(caught));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  const running = Boolean(analysis && !ANALYSIS_TERMINAL.includes(analysis.status));
  const outdated = Boolean(analysis && analysis.headSha !== pullRequest.headSha);

  return (
    <div className="findings-panel">
      <div className="analysis-bar">
        <div>
          <span className="section-label">EVIDENCE-BACKED REVIEW</span>
          <h2>{analysis ? statusHeadline(analysis) : "No review yet for this commit"}</h2>
          {analysis && (
            <p>
              Pinned to <code>{analysis.headSha.slice(0, 12)}</code> · {analysis.contextFileCount} file
              {analysis.contextFileCount === 1 ? "" : "s"} in context
              {analysis.llmModel ? ` · ${analysis.llmModel}` : ""} · started {timeAgo(analysis.createdAt)}
            </p>
          )}
        </div>
        <button
          className="primary-button compact"
          onClick={() => void startAnalysis()}
          disabled={busy || running || !capabilities.review.configured}
        >
          {analysis ? "Review current head" : "Review this pull request"}
        </button>
      </div>

      {!capabilities.review.configured && (
        <div className="warning-banner">
          Set <code>GEMINI_API_KEY</code> on the backend to enable review and test generation.
        </div>
      )}
      {error && (
        <div className="error-banner">
          <strong>Request failed</strong>
          <span>{error}</span>
        </div>
      )}
      {running && analysis && (
        <div className="progress-bar" role="progressbar" aria-valuenow={analysis.progressPercent}>
          <span style={{ width: `${Math.max(analysis.progressPercent, 5)}%` }} />
        </div>
      )}
      {outdated && analysis && !running && (
        <div className="warning-banner">
          This review examined <code>{analysis.headSha.slice(0, 12)}</code>, but the pull request is now at{" "}
          <code>{pullRequest.headSha.slice(0, 12)}</code>. Run a new review before acting on it.
        </div>
      )}
      {analysis?.status === "FAILED" && analysis.error && (
        <div className="error-banner">
          <strong>{analysis.error.code}</strong>
          <span>{analysis.error.message}</span>
        </div>
      )}
      {analysis?.status === "STALE" && (
        <div className="warning-banner">
          The head commit moved while this review was running, so its evidence was discarded.
        </div>
      )}

      {analysis?.status === "COMPLETED" && findings.length === 0 && (
        <EmptyState
          title="No defects found on the changed lines"
          copy={`ReviewForge examined ${analysis.contextFileCount} file${
            analysis.contextFileCount === 1 ? "" : "s"
          } at this commit and found nothing it could prove.`}
        />
      )}

      <div className="finding-list">
        {findings.map((finding) => (
          <FindingCard
            key={finding.id}
            finding={finding}
            repository={repository}
            capabilities={capabilities}
            expanded={expanded === finding.id}
            onToggle={() => setExpanded(expanded === finding.id ? null : finding.id)}
            onRepositoryChange={onRepositoryChange}
          />
        ))}
      </div>

      {rejected.length > 0 && (
        <div className="rejected-block">
          <button className="text-button" onClick={() => setShowRejected(!showRejected)}>
            {showRejected ? "Hide" : "Show"} {rejected.length} discarded claim
            {rejected.length === 1 ? "" : "s"}
          </button>
          {showRejected && (
            <ul>
              {rejected.map((rejection, index) => (
                <li key={index}>
                  <code>{rejection.reasonCode}</code>
                  <span>{rejection.reasonDetail}</span>
                  {rejection.filePath && (
                    <small>
                      {rejection.filePath}
                      {rejection.startLine ? `:${rejection.startLine}` : ""}
                    </small>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

function FindingCard({
  finding,
  repository,
  capabilities,
  expanded,
  onToggle,
  onRepositoryChange,
}: {
  finding: Finding;
  repository: Repository;
  capabilities: Capabilities;
  expanded: boolean;
  onToggle: () => void;
  onRepositoryChange: (repository: Repository) => void;
}) {
  return (
    <article className={expanded ? "finding-row expanded" : "finding-row"}>
      <button className="finding-summary" onClick={onToggle} aria-expanded={expanded}>
        <span className={`severity-chip ${finding.severity.toLowerCase()}`}>{finding.severity}</span>
        <span className="finding-title">
          <strong>{finding.title}</strong>
          <small>
            {finding.filePath}:{finding.startLine}
            {finding.endLine !== finding.startLine ? `–${finding.endLine}` : ""} · {finding.category} ·{" "}
            {Math.round(finding.confidence * 100)}% confidence
          </small>
        </span>
        <span className="chevron">{expanded ? "−" : "+"}</span>
      </button>

      {expanded && (
        <div className="finding-detail">
          <dl>
            <dt>Evidence</dt>
            <dd><code>{finding.evidence}</code></dd>
            <dt>Why it is wrong</dt>
            <dd>{finding.explanation}</dd>
            <dt>Failure scenario</dt>
            <dd>{finding.failureScenario}</dd>
            <dt>Suggested fix</dt>
            <dd>{finding.suggestedFix}</dd>
          </dl>
          <GeneratedTestPanel
            finding={finding}
            repository={repository}
            capabilities={capabilities}
            onRepositoryChange={onRepositoryChange}
          />
        </div>
      )}
    </article>
  );
}

function statusHeadline(analysis: Analysis): string {
  switch (analysis.status) {
    case "QUEUED":
      return "Queued for review";
    case "BUILDING_CONTEXT":
      return "Reading the changed files";
    case "ANALYZING":
      return "Analyzing the diff";
    case "VALIDATING":
      return "Validating every claim";
    case "COMPLETED":
      return `${analysis.findingCount} finding${analysis.findingCount === 1 ? "" : "s"}`;
    case "FAILED":
      return "Review failed";
    case "STALE":
      return "Review superseded by a new commit";
    default:
      return "Review cancelled";
  }
}
