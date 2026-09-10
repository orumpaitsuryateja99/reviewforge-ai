"use client";

import { parseUnifiedDiff } from "@/lib/diff";
import type { ChangedFile, Finding, PullRequestDetail } from "@/lib/types";

export function PullRequestDiff({
  detail,
  findings,
}: {
  detail: PullRequestDetail;
  findings: Finding[];
}) {
  return (
    <div className="diff-content">
      {detail.filesTruncated && (
        <div className="warning-banner">
          GitHub returned its maximum of 300 files for this exact-commit comparison. This view is incomplete.
        </div>
      )}
      <div className="file-summary">
        <strong>
          {detail.files.length} changed {detail.files.length === 1 ? "file" : "files"}
        </strong>
        <span>
          Diffs are refreshed against <code>{detail.headSha.slice(0, 12)}</code>
        </span>
      </div>
      {detail.files.length === 0 && (
        <EmptyState title="No changed files" copy="GitHub returned no file changes for this pull request." />
      )}
      {detail.files.map((file) => (
        <DiffFile
          key={file.path}
          file={file}
          findings={findings.filter((finding) => finding.filePath === file.path)}
        />
      ))}
    </div>
  );
}

function DiffFile({ file, findings }: { file: ChangedFile; findings: Finding[] }) {
  const lines = file.patch ? parseUnifiedDiff(file.patch) : [];
  const flagged = new Map<number, Finding>();
  for (const finding of findings) {
    for (let line = finding.startLine; line <= finding.endLine; line++) {
      if (!flagged.has(line)) flagged.set(line, finding);
    }
  }

  return (
    <article className="diff-file" id={`file-${file.path}`}>
      <header>
        <div>
          <span className={`file-status ${file.status.toLowerCase()}`}>{file.status}</span>
          <code>{file.path}</code>
        </div>
        <div className="change-count">
          {findings.length > 0 && <span className="finding-flag">{findings.length} finding{findings.length === 1 ? "" : "s"}</span>}
          <span className="added">+{file.additions}</span>
          <span className="deleted">−{file.deletions}</span>
        </div>
      </header>
      {file.previousPath && file.previousPath !== file.path && (
        <div className="renamed-from">
          renamed from <code>{file.previousPath}</code>
        </div>
      )}
      {file.patchTruncated ? (
        <div className="patch-unavailable">
          GitHub did not provide a patch for this file. It may be binary or too large to render.
        </div>
      ) : (
        <div className="diff-table" role="table" aria-label={`Diff for ${file.path}`}>
          {lines.map((diffLine, index) => {
            const finding = diffLine.newLine === null ? undefined : flagged.get(diffLine.newLine);
            return (
              <div
                className={`diff-line ${diffLine.kind}${finding ? " flagged" : ""}`}
                role="row"
                key={`${index}-${diffLine.oldLine}-${diffLine.newLine}`}
                title={finding ? `${finding.severity}: ${finding.title}` : undefined}
              >
                <span className="line-no" role="cell">{diffLine.oldLine ?? ""}</span>
                <span className="line-no" role="cell">{diffLine.newLine ?? ""}</span>
                <span className="diff-marker" role="cell">{diffLine.marker}</span>
                <code role="cell">{diffLine.content || " "}</code>
              </div>
            );
          })}
        </div>
      )}
    </article>
  );
}

/** Renders a patch ReviewForge produced, using the same reader as the pull-request diff. */
export function PatchPreview({ patch, label }: { patch: string; label: string }) {
  return (
    <div className="diff-table patch-preview" role="table" aria-label={label}>
      {parseUnifiedDiff(patch).map((line, index) => (
        <div className={`diff-line ${line.kind}`} role="row" key={index}>
          <span className="line-no" role="cell">{line.newLine ?? ""}</span>
          <span className="diff-marker" role="cell">{line.marker}</span>
          <code role="cell">{line.content || " "}</code>
        </div>
      ))}
    </div>
  );
}

export function EmptyState({
  title,
  copy,
  children,
}: {
  title: string;
  copy: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="empty-state">
      <strong>{title}</strong>
      <p>{copy}</p>
      {children}
    </div>
  );
}
