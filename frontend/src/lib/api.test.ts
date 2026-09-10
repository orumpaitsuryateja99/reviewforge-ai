import { afterEach, describe, expect, it, vi } from "vitest";
import { api, duration, message, timeAgo } from "@/lib/api";

function respond(body: unknown, init: ResponseInit = {}) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
    ...init,
  });
}

describe("api", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("returns the parsed body and never sends credentials cross-origin", async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond({ status: "UP" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(api<{ status: string }>("/api/platform/health")).resolves.toEqual({ status: "UP" });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/platform/health",
      expect.objectContaining({ cache: "no-store", credentials: "same-origin" }),
    );
  });

  it("surfaces the problem detail from a failed request", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        respond({ code: "ANALYSIS_IN_PROGRESS", detail: "Analysis 7 is already running." }, { status: 409 }),
      ),
    );

    await expect(api("/api/platform/analyses")).rejects.toThrow("Analysis 7 is already running.");
  });

  it("falls back to the status code when the error carries no JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("gateway down", { status: 502 })));

    await expect(api("/api/platform/analyses")).rejects.toThrow("Request failed with status 502.");
  });

  it("returns undefined for a 204 and sets JSON content type when sending a body", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(api("/api/platform/auth/github/logout", { method: "POST", body: "{}" })).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({ "content-type": "application/json" });
  });
});

describe("formatters", () => {
  it("renders relative times in the largest whole unit", () => {
    const now = Date.now();
    expect(timeAgo(new Date(now - 5 * 60_000).toISOString())).toBe("5m ago");
    expect(timeAgo(new Date(now - 3 * 3_600_000).toISOString())).toBe("3h ago");
    expect(timeAgo(new Date(now - 2 * 86_400_000).toISOString())).toBe("2d ago");
  });

  it("renders run durations and an unknown duration", () => {
    expect(duration(650)).toBe("650ms");
    expect(duration(7131)).toBe("7.1s");
    expect(duration(undefined)).toBe("—");
  });

  it("normalizes unknown throwables into a message", () => {
    expect(message(new Error("boom"))).toBe("boom");
    expect(message("boom")).toBe("An unexpected error occurred.");
  });
});
