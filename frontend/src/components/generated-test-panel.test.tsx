import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { GeneratedTestPanel } from "@/components/generated-test-panel";
import type { Capabilities, Finding, GeneratedTest, Repository, TestRun } from "@/lib/types";

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
  failureScenario: "Two reservations interleave.",
  suggestedFix: "Reserve atomically.",
  confidence: 0.92,
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

const proposed: GeneratedTest = {
  id: "test-1",
  findingId: "finding-1",
  analysisId: "analysis-1",
  status: "PROPOSED",
  targetFilePath: "src/test/java/com/acme/OrderServiceReviewForgeTest.java",
  unifiedDiff: [
    "diff --git a/src/test/java/com/acme/OrderServiceReviewForgeTest.java b/src/test/java/com/acme/OrderServiceReviewForgeTest.java",
    "new file mode 100644",
    "--- /dev/null",
    "+++ b/src/test/java/com/acme/OrderServiceReviewForgeTest.java",
    "@@ -0,0 +1,2 @@",
    "+class OrderServiceReviewForgeTest {",
    "+}",
  ].join("\n"),
  fileContent: "class OrderServiceReviewForgeTest {}",
  rationale: "Fails because availability is checked outside the lock.",
  createdAt: new Date().toISOString(),
};

const passedRun: TestRun = {
  id: "run-1",
  generatedTestId: "test-1",
  status: "PASSED",
  commandProfile: "MAVEN_SINGLE_TEST",
  exitCode: 0,
  testsRun: 1,
  testsPassed: 1,
  testsFailed: 0,
  testsSkipped: 0,
  stdout: "Tests run: 1, Failures: 0",
  timedOut: false,
  durationMillis: 7131,
  attempts: 1,
  createdAt: new Date().toISOString(),
};

function renderPanel(overrides: {
  tests?: GeneratedTest[];
  runs?: TestRun[];
  repository?: Repository;
  capabilities?: Capabilities;
  onPost?: (url: string, body: unknown) => unknown;
  onRepositoryChange?: (repository: Repository) => void;
}) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const json = (body: unknown) =>
      new Response(body === undefined ? null : JSON.stringify(body), {
        status: 200,
        headers: { "content-type": "application/json" },
      });

    if (init?.method === "POST") {
      return json(overrides.onPost?.(url, init.body ? JSON.parse(String(init.body)) : undefined) ?? {});
    }
    if (url.startsWith("/api/platform/findings/finding-1/generated-tests")) return json(overrides.tests ?? []);
    if (url.startsWith("/api/platform/generated-tests/test-1/runs")) return json(overrides.runs ?? []);
    if (url.startsWith("/api/platform/generated-tests/test-1")) return json((overrides.tests ?? [])[0]);
    if (url.startsWith("/api/platform/test-runs/run-1")) return json(passedRun);
    throw new Error(`unexpected request: ${url}`);
  });
  vi.stubGlobal("fetch", fetchMock);

  render(
    <GeneratedTestPanel
      finding={finding}
      repository={overrides.repository ?? repository}
      capabilities={overrides.capabilities ?? capabilities}
      onRepositoryChange={overrides.onRepositoryChange ?? (() => {})}
    />,
  );
  return fetchMock;
}

describe("GeneratedTestPanel", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers generation and states that the server owns the path and command", async () => {
    renderPanel({});

    expect(await screen.findByRole("button", { name: /Generate failing test/ })).toBeDefined();
    expect(screen.getByText(/server chooses the file path and the command/)).toBeDefined();
  });

  it("previews the proposed patch and its target path", async () => {
    renderPanel({ tests: [proposed] });

    expect(await screen.findByText(proposed.targetFilePath)).toBeDefined();
    expect(screen.getByText("PROPOSED")).toBeDefined();
    expect(screen.getByRole("table", { name: /Patch for/ })).toBeDefined();
    expect(screen.getByText("class OrderServiceReviewForgeTest {")).toBeDefined();
    expect(screen.getByText(/Approval never writes to your repository/)).toBeDefined();
  });

  it("sends an explicit approval decision", async () => {
    const bodies: unknown[] = [];
    renderPanel({
      tests: [proposed],
      onPost: (url, body) => {
        bodies.push({ url, body });
        return { ...proposed, status: "APPROVED", approvedAt: new Date().toISOString() };
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: "Approve patch" }));

    await waitFor(() => expect(screen.getByText("APPROVED")).toBeDefined());
    expect(bodies).toEqual([
      { url: "/api/platform/generated-tests/test-1/approval", body: { approved: true } },
    ]);
  });

  it("requires an explicit repository opt-in before offering a run", async () => {
    const approved = { ...proposed, status: "APPROVED" as const };
    renderPanel({ tests: [approved] });

    expect(await screen.findByRole("button", { name: /Allow test execution for acme\/orders/ })).toBeDefined();
    expect(screen.queryByRole("button", { name: /Run in isolated runner/ })).toBeNull();
    expect(screen.getByText(/Execution is off by default/)).toBeDefined();
  });

  it("shows run counts and the command profile once a run finishes", async () => {
    const approved = { ...proposed, status: "APPROVED" as const };
    renderPanel({
      tests: [approved],
      runs: [passedRun],
      repository: { ...repository, testExecutionAllowed: true },
    });

    expect(await screen.findByText("PASSED")).toBeDefined();
    expect(screen.getByText(/1 run · 1 passed · 0 failed/)).toBeDefined();
    expect(screen.getByText(/MAVEN_SINGLE_TEST · exit 0 · 7.1s/)).toBeDefined();
  });

  it("says the runner is unavailable rather than offering a dead button", async () => {
    const approved = { ...proposed, status: "APPROVED" as const };
    renderPanel({
      tests: [approved],
      repository: { ...repository, testExecutionAllowed: true },
      capabilities: { ...capabilities, testRunner: false },
    });

    expect(await screen.findByText(/isolated runner is not configured/)).toBeDefined();
    expect(screen.queryByRole("button", { name: /Run in isolated runner/ })).toBeNull();
  });
});
