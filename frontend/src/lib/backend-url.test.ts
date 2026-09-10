import { describe, expect, it } from "vitest";
import { backendUrl } from "@/lib/backend-url";

describe("backendUrl", () => {
  it("uses a complete URL when supplied", () => {
    expect(backendUrl({ BACKEND_INTERNAL_URL: "http://backend:8080/" })).toBe("http://backend:8080");
  });

  it("turns a managed private host and port into an HTTP URL", () => {
    expect(backendUrl({ BACKEND_INTERNAL_HOSTPORT: "reviewforge-api:10000" })).toBe(
      "http://reviewforge-api:10000",
    );
  });

  it("falls back to the local API", () => {
    expect(backendUrl({})).toBe("http://localhost:8080");
  });
});
