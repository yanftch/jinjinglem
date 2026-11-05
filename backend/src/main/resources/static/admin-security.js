let adminCsrfToken;

if (window.self !== window.top) document.documentElement.classList.add("admin-embedded");

function handleAdminResponse(response) {
  if (response.status === 401 || (response.redirected && new URL(response.url).pathname === "/login.html")) {
    window.top.location.replace("/login.html");
  }
  return response;
}

async function adminFetch(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  if (["GET", "HEAD", "OPTIONS", "TRACE"].includes(method)) {
    return fetch(path, { credentials: "same-origin", ...options }).then(handleAdminResponse);
  }
  if (adminCsrfToken === undefined) {
    const tokenResponse = await fetch("/api/v1/admin/csrf", { cache: "no-store", credentials: "same-origin" });
    if (!tokenResponse.ok) return handleAdminResponse(tokenResponse);
    adminCsrfToken = (await tokenResponse.json()).token;
  }
  const headers = new Headers(options.headers);
  headers.set("X-XSRF-TOKEN", adminCsrfToken);
  return fetch(path, { ...options, headers, credentials: "same-origin" }).then(handleAdminResponse);
}
