let adminCsrfToken;

async function adminFetch(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  if (["GET", "HEAD", "OPTIONS", "TRACE"].includes(method)) {
    return fetch(path, options);
  }
  if (adminCsrfToken === undefined) {
    const tokenResponse = await fetch("/api/v1/admin/csrf", { cache: "no-store" });
    if (!tokenResponse.ok) return tokenResponse;
    adminCsrfToken = (await tokenResponse.json()).token;
  }
  const headers = new Headers(options.headers);
  headers.set("X-XSRF-TOKEN", adminCsrfToken);
  return fetch(path, { ...options, headers });
}
