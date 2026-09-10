import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReviewDashboard } from "@/components/review-dashboard";

function stubLanding(configured: boolean) {
  const fetchMock = vi.fn(async (url: string) => {
    const payload = url.includes("/auth/github/session")
      ? { configured, authenticated: false }
      : url.includes("/capabilities")
        ? {
            github: configured,
            review: { configured: false, provider: "gemini", model: null },
            testRunner: true,
          }
        : url.includes("/health")
          ? {
              service: "reviewforge-control-plane",
              status: "UP",
              version: "0.1.0",
              timestamp: new Date().toISOString(),
            }
          : null;

    if (!payload) throw new Error(`unexpected request: ${url}`);
    return new Response(JSON.stringify(payload), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  });

  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("ReviewDashboard landing page", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows a clear project introduction and setup help when GitHub is not configured", async () => {
    stubLanding(false);
    render(<ReviewDashboard />);

    expect(await screen.findByRole("heading", { name: "Review Java pull requests with AI" })).toBeDefined();
    expect(screen.getByText("Java 25 project")).toBeDefined();
    expect(screen.getByText("Add your GitHub App details")).toBeDefined();
    expect(screen.getByText("Needs configuration")).toBeDefined();
    expect(screen.getByText("Sign in with GitHub").getAttribute("aria-disabled")).toBe("true");
    expect(await screen.findByText("Operational")).toBeDefined();
  });

  it("links to GitHub sign-in after configuration", async () => {
    stubLanding(true);
    render(<ReviewDashboard />);

    const signIn = await screen.findByRole("link", { name: "Sign in with GitHub" });
    expect(signIn.getAttribute("href")).toBe("/api/platform/auth/github/login");
    expect(screen.queryByText("Add your GitHub App details")).toBeNull();
  });
});
