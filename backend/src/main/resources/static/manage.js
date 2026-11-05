const pages = {
  map: { title: "探头分布", path: "/" },
  cameras: { title: "探头管理", path: "/admin.html" },
  sync: { title: "定时抓取", path: "/sync.html" },
  review: { title: "抓取审核", path: "/review.html" },
  reports: { title: "用户上报审核", path: "/reports.html" },
  comments: { title: "新增评论", path: "/daily-comments.html" }
};
const frame = document.getElementById("pageFrame");
const requested = new URLSearchParams(window.location.search).get("page") || "cameras";
const initial = Object.hasOwn(pages, requested) ? requested : "cameras";

function selectPage(page, path, replace = false) {
  const info = pages[page];
  document.getElementById("currentPage").textContent = info.title;
  document.getElementById("pageTitle").textContent = info.title;
  document.title = `${info.title} · 进京了管理后台`;
  document.querySelectorAll("[data-page]").forEach(link => {
    const active = link.dataset.page === page;
    link.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  const address = new URL("/manage.html", window.location.origin);
  address.searchParams.set("page", page);
  if (page === "review") {
    const runId = new URLSearchParams(path.split("?")[1] || "").get("runId");
    if (runId && /^\d+$/.test(runId)) address.searchParams.set("runId", runId);
  }
  window.history[replace ? "replaceState" : "pushState"]({ page }, "", address);
}

function framePath(page) {
  let path = pages[page].path;
  if (page === "review") {
    const runId = new URLSearchParams(window.location.search).get("runId");
    if (runId && /^\d+$/.test(runId)) path += `?runId=${runId}`;
  }
  return path;
}

frame.addEventListener("load", () => {
  try {
    const path = frame.contentWindow.location.pathname;
    if (path === "/login.html") { window.location.replace("/login.html"); return; }
    const page = Object.keys(pages).find(key => pages[key].path === path);
    if (page) selectPage(page, path + frame.contentWindow.location.search, true);
  } catch (_) { /* Frame navigation stays same-origin; ignore an unexpected external page. */ }
});

document.querySelectorAll("[data-page]").forEach(link => link.addEventListener("click", event => {
  event.preventDefault();
  const page = link.dataset.page;
  selectPage(page, pages[page].path);
  frame.src = pages[page].path;
}));
window.addEventListener("popstate", () => {
  const page = new URLSearchParams(window.location.search).get("page") || "cameras";
  if (!Object.hasOwn(pages, page)) return;
  frame.src = framePath(page);
  selectPage(page, frame.src, true);
});
selectPage(initial, framePath(initial), true);
frame.src = framePath(initial);

document.getElementById("logoutButton").addEventListener("click", async () => {
  const button = document.getElementById("logoutButton");
  button.disabled = true;
  try {
    const response = await adminFetch("/logout", { method: "POST", redirect: "manual" });
    if (response.status === 401) return;
    window.location.replace("/login.html?logout");
  } catch (_) {
    button.disabled = false;
    alert("退出失败，请稍后重试。");
  }
});
