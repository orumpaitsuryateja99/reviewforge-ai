"use client";

import { useEffect, useState } from "react";

type Health = {
  service: string;
  status: "UP";
  version: string;
  timestamp: string;
};

type State =
  | { kind: "loading" }
  | { kind: "up"; data: Health }
  | { kind: "down" };

export function SystemStatus() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    fetch("/api/platform/health", { signal: controller.signal, cache: "no-store" })
      .then(async (response) => {
        if (!response.ok) throw new Error("Health check failed");
        return (await response.json()) as Health;
      })
      .then((data) => setState({ kind: "up", data }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setState({ kind: "down" });
      });

    return () => controller.abort();
  }, []);

  return (
    <div className="status-fact live-status">
      <span>Control plane</span>
      {state.kind === "loading" && <strong><i className="pulse neutral" />Checking API…</strong>}
      {state.kind === "up" && <strong title={`Version ${state.data.version}`}><i className="pulse" />Operational</strong>}
      {state.kind === "down" && <strong><i className="pulse down" />Unavailable</strong>}
    </div>
  );
}

