import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FindingsPanel } from "@/components/findings-panel";
import type { Analysis, Capabilities, Finding, PullRequestDetail, Repository } from "@/lib/types";

const HEAD_SHA = "1".repeat(40);

const pullRequest: PullRequestDetail = {
  id: "pr-1",
  repositoryId: "repo-1",
  number: 7,
  title: "Reserve inventory",
  authorLogin: "octo",
  state: "OPEN",
  headSha: HEAD_SHA,
  updatedAt: new Date().toISOString(),
  baseRef: "main",
  baseSha: "2".repeat(40),
  headRef: "feature",
  files: [],
  filesTruncated: false,
};

const repository: Repository = {
  id: "repo-1",
  githubRepositoryId: 555,
  fullName: "acme/orders",
  defaultBranch: "main",
  privateRepository: false,
  testExecutionAllowed: false,
};

const capabilities: Capabilities = {
  github: true,
  review: { configured: true, provider: "gemini", model: "gemini-2.5-flash" },
  testRunner: true,
};

const completedAnalysis: Analysis = {
  id: "analysis-1",
  pullRequestId: "pr-1",
  headSha: HEAD_SHA,
  currentHeadSha: HEAD_SHA,
  status: "COMPLETED",
  progressPercent: 100,
  stale: false,
  findingCount: 1,
  contextFileCount: 1,
  llmModel: "gemini-2.5-flash",
  createdAt: new Date().toISOString(),
};

const finding: Finding = {
  id: "finding-1",
  analysisId: "analysis-1",
  category: "CONCURRENCY",
  severity: "HIGH",
  title: "Check-then-act race can oversell inventory",
  explanation: "Availability is checked outside the lock.",
  filePath: "src/main/java/com/acme/OrderService.java",
  startLine: 5,
  endLine: 6,
  evidence: "if (inventory.available(sku)) {",
  failureScenario: "Two reservations interleave on the last unit.",
  suggestedFix: "Reserve atomically.",
  confidence: 0.92,
};

type Routes = Record<string, unknown>;

function stubFetch(routes: Routes) {
  const fetchMock = vi.fn(async (url: string) => {
    const match = Object.keys(routes).find((route) => url.startsWith(route));
    if (!match) throw new Error(`unexpected request: ${url}`);
    return new Response(JSON.stringify(routes[match]), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderPanel(overrides: Partial<Capabilities> = {}) {
  return render(
    <FindingsPanel
      pullRequest={pullRequest}
      repository={repository}
      capabilities={{ ...capabilities, ...overrides }}
      onFindings={() => {}}
      onRepositoryChange={() => {}}
    />,
  );
}

describe("FindingsPanel", () => {
  beforeEach(() => vi.useRealTimers());
  afterEach(() => vi.unstubAllGlobals());

  it("shows a finding with its evidence once the analysis completes", async () => {
    stubFetch({
      "/api/platform/pull-requests/pr-1/analyses": [completedAnalysis],
      "/api/platform/analyses/analysis-1/findings": [finding],
      "/api/platform/analyses/analysis-1/rejected-findings": [],
      "/api/platform/findings/finding-1/generated-tests": [],
    });

    renderPanel();

    expect(await screen.findByText(finding.title)).toBeDefined();
    expect(screen.getByText("HIGH")).toBeDefined();
    expect(screen.getByText(/1 finding$/)).toBeDefined();

    await userEvent.click(screen.getByRole("button", { name: /Check-then-act race/ }));

    expect(await screen.findByText("Evidence")).toBeDefined();
    expect(screen.getByText("if (inventory.available(sku)) {")).toBeDefined();
    expect(screen.getByText("Two reservations interleave on the last unit.")).toBeDefined();
  });

  it("explains an empty result instead of showing nothing", async () => {
    stubFetch({
      "/api/platform/pull-requests/pr-1/analyses": [{ ...completedAnalysis, findingCount: 0 }],
      "/api/platform/analyses/analysis-1/findings": [],
      "/api/platform/analyses/analysis-1/rejected-findings": [],
    });

    renderPanel();

    expect(await screen.findByText("No defects found on the changed lines")).toBeDefined();
  });

  it("lists discarded claims with the reason each was dropped", async () => {
    stubFetch({
      "/api/platform/pull-requests/pr-1/analyses": [completedAnalysis],
      "/api/platform/analyses/analysis-1/findings": [finding],
      "/api/platform/analyses/analysis-1/rejected-findings": [
        {
          reasonCode: "UNKNOWN_FILE",
          reasonDetail: "No file with that path was supplied for this analysis.",
          filePath: "src/main/java/com/acme/Ghost.java",
        },
      ],
      "/api/platform/findings/finding-1/generated-tests": [],
    });

    renderPanel();

    await userEvent.click(await screen.findByRole("button", { name: /Show 1 discarded claim/ }));

    expect(screen.getByText("UNKNOWN_FILE")).toBeDefined();
    expect(screen.getByText("No file with that path was supplied for this analysis.")).toBeDefined();
  });

  it("warns when the pull request has moved past the analyzed commit", async () => {
    stubFetch({
      "/api/platform/pull-requests/pr-1/analyses": [
        { ...completedAnalysis, headSha: "9".repeat(40), stale: true },
      ],
      "/api/platform/analyses/analysis-1/findings": [],
      "/api/platform/analyses/analysis-1/rejected-findings": [],
    });

    renderPanel();

    expect(await screen.findByText(/but the pull request is now at/)).toBeDefined();
  });

  it("disables review and says why when no model provider is configured", async () => {
    stubFetch({ "/api/platform/pull-requests/pr-1/analyses": [] });

    renderPanel({ review: { configured: false, provider: "gemini", model: null } });

    const button = await screen.findByRole("button", { name: /Review this pull request/ });
    expect(button.hasAttribute("disabled")).toBe(true);
    expect(screen.getByText(/GEMINI_API_KEY/)).toBeDefined();
  });

  it("queues an analysis and follows it to completion", async () => {
    let analysesCalls = 0;
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      const json = (body: unknown) =>
        new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });

      if (url.startsWith("/api/platform/pull-requests/pr-1/analyses") && init?.method === "POST") {
        return json({ jobId: "analysis-1", status: "QUEUED", statusUrl: "/api/v1/analyses/analysis-1" });
      }
      if (url.startsWith("/api/platform/pull-requests/pr-1/analyses")) return json([]);
      if (url.startsWith("/api/platform/analyses/analysis-1/findings")) return json([finding]);
      if (url.startsWith("/api/platform/analyses/analysis-1/rejected-findings")) return json([]);
      if (url.startsWith("/api/platform/findings/finding-1/generated-tests")) return json([]);
      if (url.startsWith("/api/platform/analyses/analysis-1")) {
        analysesCalls += 1;
        return json(analysesCalls === 1 ? { ...completedAnalysis, status: "ANALYZING", progressPercent: 45 } : completedAnalysis);
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderPanel();

    await userEvent.click(await screen.findByRole("button", { name: /Review this pull request/ }));

    expect(await screen.findByText("Analyzing the diff")).toBeDefined();
    expect(screen.getByRole("progressbar")).toBeDefined();

    await waitFor(() => expect(screen.getByText(finding.title)).toBeDefined(), { timeout: 5000 });
  });
});
