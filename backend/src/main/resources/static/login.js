if (window.self !== window.top) window.top.location.replace("/login.html");

const params = new URLSearchParams(window.location.search);
const message = document.getElementById("loginMessage");
if (params.has("error")) {
  message.textContent = "账号或密码不正确，请重试。";
  message.hidden = false;
} else if (params.has("logout")) {
  message.textContent = "已安全退出后台。";
  message.hidden = false;
}

fetch("/api/v1/auth/csrf", { credentials: "same-origin", cache: "no-store" })
  .then(response => {
    if (!response.ok) throw new Error("无法加载登录令牌，请刷新页面重试。");
    return response.json();
  })
  .then(data => {
    document.getElementById("csrfToken").value = data.token;
    document.getElementById("loginButton").disabled = false;
  })
  .catch(error => {
    message.textContent = error.message;
    message.hidden = false;
  });
