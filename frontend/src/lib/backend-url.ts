/** Resolves either a complete backend URL or a managed host's private host:port value. */
export function backendUrl(
  environment: Readonly<Record<string, string | undefined>> = process.env,
): string {
  const configuredUrl = environment.BACKEND_INTERNAL_URL?.trim();
  if (configuredUrl) return configuredUrl.replace(/\/$/, "");

  const hostPort = environment.BACKEND_INTERNAL_HOSTPORT?.trim();
  if (hostPort) return `http://${hostPort.replace(/^https?:\/\//, "").replace(/\/$/, "")}`;

  return "http://localhost:8080";
}
