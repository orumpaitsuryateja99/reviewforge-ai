type Problem = { detail?: string; code?: string };

/** Calls the Next.js proxy, which forwards to the control plane with the session cookie. */
export async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    cache: "no-store",
    credentials: "same-origin",
    headers: init?.body ? { "content-type": "application/json", ...init?.headers } : init?.headers,
  });

  if (!response.ok) {
    let problem: Problem = {};
    try {
      problem = (await response.json()) as Problem;
    } catch {
      /* upstream returned no JSON body */
    }
    throw new Error(problem.detail ?? `Request failed with status ${response.status}.`);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export function message(error: unknown): string {
  return error instanceof Error ? error.message : "An unexpected error occurred.";
}

export function timeAgo(value: string): string {
  const elapsed = Date.now() - new Date(value).getTime();
  const minutes = Math.max(1, Math.floor(elapsed / 60_000));
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function duration(millis?: number): string {
  if (millis === undefined) return "—";
  if (millis < 1000) return `${millis}ms`;
  return `${(millis / 1000).toFixed(1)}s`;
}
