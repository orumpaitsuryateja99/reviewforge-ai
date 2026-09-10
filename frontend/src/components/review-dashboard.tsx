"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { EmptyState, PullRequestDiff } from "@/components/diff-view";
import { FindingsPanel } from "@/components/findings-panel";
import { ReportPanel } from "@/components/report-panel";
import { SystemStatus } from "@/components/system-status";
import { api, message, timeAgo } from "@/lib/api";
import type {
  Capabilities,
  Finding,
  PullRequest,
  PullRequestDetail,
  Repository,
  Session,
  User,
} from "@/lib/types";

const PROJECT_BADGE = "Java 25 project";

export function ReviewDashboard() {
  const [session, setSession] = useState<Session | null>(null);
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api<Session>("/api/platform/auth/github/session"),
      api<Capabilities>("/api/platform/capabilities"),
    ])
      .then(([loadedSession, loadedCapabilities]) => {
        setSession(loadedSession);
        setCapabilities(loadedCapabilities);
      })
      .catch((error: unknown) => setSessionError(message(error)));
  }, []);

  if (sessionError) {
    return <CenteredState title="The dashboard could not start" copy={sessionError} />;
  }
  if (!session || !capabilities) {
    return (
      <CenteredState
        title="Opening ReviewForge"
        copy="Checking the control plane and your GitHub session…"
        loading
      />
    );
  }
  if (!session.authenticated || !session.user) {
    return <Landing configured={session.configured} capabilities={capabilities} />;
  }
  return (
    <Workspace
      user={session.user}
      capabilities={capabilities}
      onSignedOut={() => setSession({ ...session, authenticated: false, user: undefined })}
    />
  );
}

function Landing({ configured, capabilities }: { configured: boolean; capabilities: Capabilities }) {
  return (
    <main className="landing-shell">
      <Nav badge={PROJECT_BADGE} />
      <section className="hero" id="top">
        <div className="hero-copy">
          <div className="eyebrow">AI CODE REVIEW FOR JAVA</div>
          <h1>Review Java pull requests with AI</h1>
          <p className="lede">
            Connect a GitHub repository, choose a pull request, and check the changed code for bugs.
            Every result includes the source line and can be tested with a generated JUnit test.
          </p>
          <div className="hero-actions">
            {configured ? (
              <Link className="primary-button" href="/api/platform/auth/github/login" prefetch={false}>
                Sign in with GitHub
              </Link>
            ) : (
              <span className="primary-button disabled" aria-disabled="true">Sign in with GitHub</span>
            )}
            <span className="availability">
              {configured ? "GitHub credentials stay in the backend" : "Add the GitHub settings to .env first"}
            </span>
          </div>
        </div>
        <div className="code-sample" aria-label="Example exact-head review">
          <div className="code-header">
            <span>Example review</span>
            <span className="sha">OrderService.java</span>
          </div>
          <pre><code><span className="line-number">41</span>  if (inventory.available(sku)) &#123;{"\n"}<span className="line-number active">42</span>    inventory.reserve(sku, quantity);{"\n"}<span className="line-number">43</span>  &#125;</code></pre>
          <div className="finding-card">
            <span className="severity">HIGH</span>
            <strong>Check-then-act race can oversell inventory</strong>
            <span className="location">OrderService.java : 41–42</span>
          </div>
        </div>
      </section>
      <section className="status-strip">
        <SystemStatus />
        <div className="status-fact"><span>GitHub App</span><strong>{configured ? "Ready to connect" : "Needs configuration"}</strong></div>
        <div className="status-fact">
          <span>Review engine</span>
          <strong>{capabilities.review.configured ? capabilities.review.model ?? "Configured" : "Needs a model key"}</strong>
        </div>
        <div className="status-fact">
          <span>Test runner</span>
          <strong>{capabilities.testRunner ? "Isolated runner configured" : "Not configured"}</strong>
        </div>
      </section>
      {!configured && (
        <section className="setup-callout">
          <span className="section-label">SETUP REQUIRED</span>
          <h2>Add your GitHub App details</h2>
          <p>Copy the five <code>GITHUB_*</code> values into <code>.env</code>, then restart the project.</p>
        </section>
      )}
      <footer><span>ReviewForge AI — Java code review project</span><span>Spring Boot · Next.js · PostgreSQL</span></footer>
    </main>
  );
}

type Tab = "diff" | "findings" | "report";

function Workspace({
  user,
  capabilities,
  onSignedOut,
}: {
  user: User;
  capabilities: Capabilities;
  onSignedOut: () => void;
}) {
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selectedRepositoryId, setSelectedRepositoryId] = useState("");
  const [pullRequests, setPullRequests] = useState<PullRequest[]>([]);
  const [selectedPullRequestId, setSelectedPullRequestId] = useState("");
  const [detail, setDetail] = useState<PullRequestDetail | null>(null);
  const [findings, setFindings] = useState<Finding[]>([]);
  const [tab, setTab] = useState<Tab>("diff");
  const [loadingRepositories, setLoadingRepositories] = useState(true);
  const [loadingPullRequests, setLoadingPullRequests] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(() => {
    if (typeof window === "undefined") return null;
    const result = new URLSearchParams(window.location.search).get("github");
    if (result === "installed") return "GitHub App installed. Repositories are now in sync.";
    if (result === "connected") return "GitHub account connected.";
    return null;
  });

  const loadRepositories = useCallback(async (refresh = true) => {
    setLoadingRepositories(true);
    setError(null);
    try {
      const data = await api<Repository[]>(`/api/platform/repositories?refresh=${refresh}`);
      setRepositories(data);
      setSelectedRepositoryId((current) => {
        const next = data.some((repository) => repository.id === current) ? current : data[0]?.id ?? "";
        if (next !== current) {
          setPullRequests([]);
          setSelectedPullRequestId("");
          setDetail(null);
          setLoadingPullRequests(Boolean(next));
        }
        return next;
      });
    } catch (caught) {
      setError(message(caught));
    } finally {
      setLoadingRepositories(false);
    }
  }, []);

  useEffect(() => {
    const githubResult = new URLSearchParams(window.location.search).get("github");
    if (githubResult) window.history.replaceState({}, "", window.location.pathname);
    const task = window.setTimeout(() => void loadRepositories(), 0);
    return () => window.clearTimeout(task);
  }, [loadRepositories]);

  useEffect(() => {
    if (!selectedRepositoryId) return;
    let active = true;
    api<PullRequest[]>(`/api/platform/repositories/${selectedRepositoryId}/pull-requests`)
      .then((data) => {
        if (!active) return;
        setPullRequests(data);
        setSelectedPullRequestId((current) => {
          const next = data.some((pullRequest) => pullRequest.id === current) ? current : data[0]?.id ?? "";
          if (next !== current) {
            setDetail(null);
            setLoadingDetail(Boolean(next));
          }
          return next;
        });
      })
      .catch((caught: unknown) => active && setError(message(caught)))
      .finally(() => active && setLoadingPullRequests(false));
    return () => {
      active = false;
    };
  }, [selectedRepositoryId]);

  useEffect(() => {
    if (!selectedPullRequestId) return;
    let active = true;
    api<PullRequestDetail>(`/api/platform/pull-requests/${selectedPullRequestId}`)
      .then((data) => active && setDetail(data))
      .catch((caught: unknown) => active && setError(message(caught)))
      .finally(() => active && setLoadingDetail(false));
    return () => {
      active = false;
    };
  }, [selectedPullRequestId]);

  const handleFindings = useCallback((next: Finding[]) => setFindings(next), []);
  const handleRepositoryChange = useCallback((updated: Repository) => {
    setRepositories((current) =>
      current.map((repository) => (repository.id === updated.id ? updated : repository)),
    );
  }, []);

  async function signOut() {
    try {
      await api<void>("/api/platform/auth/github/logout", { method: "POST" });
      onSignedOut();
    } catch (caught) {
      setError(message(caught));
    }
  }

  function selectRepository(repositoryId: string) {
    if (repositoryId === selectedRepositoryId) return;
    setSelectedRepositoryId(repositoryId);
    setPullRequests([]);
    setSelectedPullRequestId("");
    setDetail(null);
    setFindings([]);
    setError(null);
    setLoadingPullRequests(true);
  }

  function selectPullRequest(pullRequestId: string) {
    if (pullRequestId === selectedPullRequestId) return;
    setSelectedPullRequestId(pullRequestId);
    setDetail(null);
    setFindings([]);
    setError(null);
    setTab("diff");
    setLoadingDetail(true);
  }

  const repository = repositories.find((candidate) => candidate.id === selectedRepositoryId) ?? null;

  return (
    <main className="workspace-shell">
      <Nav badge={PROJECT_BADGE} user={user} onSignOut={signOut} />
      {notice && (
        <div className="notice">
          <span>{notice}</span>
          <button onClick={() => setNotice(null)} aria-label="Dismiss">×</button>
        </div>
      )}
      <div className="workspace-grid">
        <aside className="repository-panel">
          <div className="panel-heading">
            <div><span className="section-label">GITHUB</span><h2>Repositories</h2></div>
            <button
              className="icon-button"
              onClick={() => void loadRepositories()}
              disabled={loadingRepositories}
              title="Sync repositories"
            >
              ↻
            </button>
          </div>
          {loadingRepositories && repositories.length === 0 && <LoadingRows />}
          {!loadingRepositories && repositories.length === 0 && (
            <EmptyState
              title="No repositories yet"
              copy="Install the GitHub App and choose the repositories ReviewForge may read."
            >
              <Link className="primary-button compact" href="/api/platform/github/installations/new" prefetch={false}>
                Install GitHub App
              </Link>
            </EmptyState>
          )}
          <div className="repository-list">
            {repositories.map((candidate) => (
              <button
                key={candidate.id}
                className={candidate.id === selectedRepositoryId ? "repository-item selected" : "repository-item"}
                onClick={() => selectRepository(candidate.id)}
              >
                <span className="repo-icon">{candidate.privateRepository ? "◈" : "◇"}</span>
                <span>
                  <strong>{candidate.fullName}</strong>
                  <small>
                    default / {candidate.defaultBranch}
                    {candidate.testExecutionAllowed ? " · test execution on" : ""}
                  </small>
                </span>
              </button>
            ))}
          </div>
          {repositories.length > 0 && (
            <Link className="secondary-button full" href="/api/platform/github/installations/new" prefetch={false}>
              Manage GitHub access
            </Link>
          )}
        </aside>

        <section className="pull-panel">
          <div className="panel-heading">
            <div><span className="section-label">SELECT ONE</span><h2>Pull requests</h2></div>
            <span className="count-badge">{pullRequests.length}</span>
          </div>
          {loadingPullRequests && <LoadingRows />}
          {!loadingPullRequests && selectedRepositoryId && pullRequests.length === 0 && (
            <EmptyState title="No open pull requests" copy="This repository has no open pull requests on GitHub." />
          )}
          {!selectedRepositoryId && !loadingRepositories && repositories.length > 0 && (
            <EmptyState title="Choose a repository" copy="Select a repository to fetch its open pull requests." />
          )}
          <div className="pull-list">
            {pullRequests.map((pullRequest) => (
              <button
                key={pullRequest.id}
                className={pullRequest.id === selectedPullRequestId ? "pull-item selected" : "pull-item"}
                onClick={() => selectPullRequest(pullRequest.id)}
              >
                <span className="pr-number">#{pullRequest.number}</span>
                <strong>{pullRequest.title}</strong>
                <small>@{pullRequest.authorLogin} · {timeAgo(pullRequest.updatedAt)}</small>
                <code>{pullRequest.headSha.slice(0, 8)}</code>
              </button>
            ))}
          </div>
        </section>

        <section className="diff-panel">
          {error && (
            <div className="error-banner">
              <strong>Request failed</strong>
              <span>{error}</span>
            </div>
          )}
          {loadingDetail && (
            <CenteredState
              title="Loading exact-head diff"
              copy="Fetching changed files directly from GitHub…"
              loading
              compact
            />
          )}
          {!loadingDetail && !detail && !error && (
            <CenteredState
              title="Select a pull request"
              copy="Its exact head commit, findings, and generated tests will appear here."
              compact
            />
          )}
          {!loadingDetail && detail && repository && (
            <>
              <header className="diff-titlebar">
                <div>
                  <span className="section-label">PR #{detail.number} · {detail.state}</span>
                  <h1>{detail.title}</h1>
                  <p>@{detail.authorLogin} · {detail.baseRef} ← {detail.headRef}</p>
                </div>
                <div className="head-sha">
                  <span>EXACT HEAD</span>
                  <code title={detail.headSha}>{detail.headSha}</code>
                </div>
              </header>

              <nav className="tab-bar">
                <TabButton current={tab} value="diff" onSelect={setTab}>Diff</TabButton>
                <TabButton current={tab} value="findings" onSelect={setTab}>
                  Findings{findings.length > 0 ? ` (${findings.length})` : ""}
                </TabButton>
                <TabButton current={tab} value="report" onSelect={setTab}>Report</TabButton>
              </nav>

              {tab === "diff" && <PullRequestDiff detail={detail} findings={findings} />}
              {tab === "findings" && (
                <FindingsPanel
                  key={detail.id}
                  pullRequest={detail}
                  repository={repository}
                  capabilities={capabilities}
                  onFindings={handleFindings}
                  onRepositoryChange={handleRepositoryChange}
                />
              )}
              {tab === "report" && <ReportPanel key={detail.id} pullRequest={detail} />}
            </>
          )}
        </section>
      </div>
    </main>
  );
}

function TabButton({
  current,
  value,
  onSelect,
  children,
}: {
  current: Tab;
  value: Tab;
  onSelect: (tab: Tab) => void;
  children: React.ReactNode;
}) {
  return (
    <button
      className={current === value ? "tab selected" : "tab"}
      onClick={() => onSelect(value)}
      aria-current={current === value}
    >
      {children}
    </button>
  );
}

function Nav({
  badge,
  user,
  onSignOut,
}: {
  badge: string;
  user?: User;
  onSignOut?: () => void;
}) {
  return (
    <nav className="topbar">
      <Link className="brand" href="/" aria-label="ReviewForge home">
        <span className="brand-mark">RF</span>
        <span>ReviewForge <b>AI</b></span>
      </Link>
      <div className="nav-meta">
        <span className="milestone">{badge}</span>
        {user && <span className="user-chip">@{user.login}</span>}
        {onSignOut && <button className="text-button" onClick={() => void onSignOut()}>Sign out</button>}
      </div>
    </nav>
  );
}

function LoadingRows() {
  return (
    <div className="loading-rows" aria-label="Loading">
      <span /><span /><span />
    </div>
  );
}

function CenteredState({
  title,
  copy,
  loading = false,
  compact = false,
}: {
  title: string;
  copy: string;
  loading?: boolean;
  compact?: boolean;
}) {
  return (
    <section className={compact ? "centered-state compact" : "centered-state"}>
      {loading && <span className="spinner" />}
      <h1>{title}</h1>
      <p>{copy}</p>
    </section>
  );
}
