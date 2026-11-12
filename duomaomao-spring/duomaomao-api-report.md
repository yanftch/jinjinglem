# 多毛毛（com.duomaomao.duomaomao）v9.0.0 — 网络接口逆向报告

> 工具链：REA 6.8.0（CLI + MCP）→ Android provider `jadx-headless-mcp` 0.7.1（JADX headless）
> 目标：`/Users/yanfeng/Documents/workspace/opencode/duomaomao.apk`
> APK SHA-256：`ca199360dd86226caa14ac5eebf8379410850cdf17df6d4969bf4ca3af0e8535`
> 包名：`com.duomaomao.duomaomao`，versionName `9.0.0`（versionCode 9），minSdk 24 / targetSdk 34
> 全部结论来自静态反编译，**未运行 APK、未发起真实请求**。

---

## 1. 结论速览

- App 只有一个自建业务后端：腾讯云 CloudBase 云托管环境
  `https://jinjing-0gxvttiv313dd8aa-1319848734.ap-shanghai.app.tcloudbase.com`
- 所有业务接口都是 **HTTPS POST + JSON**，路径为“随机化”短路径（如 `/qwvsxdvglkj`），
  且服务端可通过 `/androidCheckUpdate202609` 下发新路径覆盖本地默认值（热轮换）。
- 请求体采用 **RSA-OAEP(SHA-256) 封装一次性 AES-256 密钥 + AES-GCM 响应加密 + raw deflate 压缩**。
  不属于标准 TLS 之外的通用协议，必须实现该握手才能调通。
- 鉴权使用 **硬编码在 APK 里的静态 JWT** 作 `Authorization: Bearer`，该 JWT 的
  `is_system_admin: true`、`exp: 253402300799`（9999 年），且所有安装共用同一把 key。
- 未观察到请求签名、时间戳、nonce 或设备指纹校验（仅 `LOGIN_CODE`/`VALIDATE_TOKEN` 带 `device_id`）。

---

## 2. 传输层

| 项 | 值 |
|---|---|
| Base URL | `https://jinjing-0gxvttiv313dd8aa-1319848734.ap-shanghai.app.tcloudbase.com` |
| 方法 | `POST`（全部接口） |
| Content-Type | `application/json`（`createRequest` 里再覆盖为 `application/json`；MediaType 为 `application/json; charset=utf-8`） |
| 鉴权头 | `Authorization: Bearer <CLOUD_API_KEY>` |
| 连接/读/写超时 | 30s / 120s / 30s（普通客户端）；路径规划客户端读超时 **10 分钟** |
| 可选头 | `disableCache=true` 时追加 `Cache-Control: no-cache, no-store, must-revalidate`、`Pragma: no-cache`，并 `CacheControl.FORCE_NETWORK` |
| 日志 | `HttpLoggingInterceptor.Level.BODY`（含请求/响应体，release 里也开着） |

依据：`ev_7d8e8af9ae9f0fb5a0b31cbd24f1138af0a5595f08c70fe51586ccd7be42109b`（createRequest）、
`ev_112eaf087af27976019eb8de23a059360cc1616f79635054f45ff78a6d06007b`（构造/客户端）、
`ev_ebd6dbd4075384520e22a0b3b5645c7932623c60bafbc89dc938ad56160149c9`（BuildConfig 常量）。

---

## 3. 应用层加密协议（必须复现）

### 3.1 请求

1. 客户端生成 32 字节随机数 `raw`（AES-256 密钥）。
2. 用内置 RSA 公钥做 `RSA/ECB/OAEPPadding`，参数 `OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)` 加密 `raw`。
3. `ek = Base64(RSA(raw))`（NO_WRAP，`Base64.encodeToString(..., 2)`）。
4. 请求 JSON = 业务字段 + `"ek": "<base64>"`，例如：
   ```json
   {"OPENID":"...","page":1,"ek":"k3J...=="}
   ```

依据：`ev_8460f1ab8465516f41d3bae3066be942703e359378e396031c0c1114362a2236`（makeHandshake）、
`ev_76700c0f94b1ee454e0a68e53bc1ae77a70f6ba104cd64f6392634ffb48c48f7`（encryptedPost）。

### 3.2 响应

服务端返回的 `bytes` 可能是原始二进制，也可能是 Base64 文本（客户端用“全部字符均属 Base64 字母表”判断）。解码后的帧格式：

```
[ 12 bytes IV ][ 16 bytes GCM tag ][ ciphertext ... ]
```

- `cipher.init(DECRYPT_MODE, aesKey, GCMParameterSpec(128, IV))`
- `doFinal(ciphertext || tag)`  → 明文
- 明文是 **raw deflate** 压缩的 JSON，用 `Inflater(true)` 解压后得到业务 JSON。

依据：`ev_5493d31749401e0d0e48c2c075c24723e77079970c25258deead15fca26144b6`（open）、
`ev_a334c86a44f27e6fa9206995260533b92be7f4879c2f1d422a29bf915fddc0a5`（inflateRaw）、
`ev_bae2dd413e478ef2111718d4571359fa2fc7ed6917c44da2adf3d24bcd2eabb3`（base64 判定）。

### 3.3 内置 RSA 公钥（2048-bit，SPKI/X.509 Base64）

```
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2eOR1oDny0w7ATtRVwW92LLSRSv6jp6mXXqw7xLN+CUC1FULE5hbCgOwALohgTYnDBSj31F3JW5Dn7BYvgnKTRD1/ph+M6vm2PCi82oZOOY1akHRlmf4hnkGmgtWPprnFbgCG4KjUSGXAPr9RBIEAZ/RseObcOtq3mYqAyBiQX+xM2+iANVQ+8WTC04S+jVpY4xr3c/+DafO5jBcbhOYQgwCtafEwLU5Onf2pZtRiNG1lU0FptFwva4DEvV3MNR5A3X0cMN1w5ps62iWgYQyuueeep2c5n2UTgd1pvWeJj8doGwnw4GqU/ZkEViL6FH9UE1Mc2HEB2509rGggxS98QIDAQAB
```

依据：`ev_2941e9e9ace45d46cb1674e587752a3d1a06af5796d11e8f1b214db0952f8d21`（`DataCrypto$publicKey$2.invoke`，jadx 以 smali 形式给出常量）。

### 3.4 安全评价（基于上述观察）

- 每个请求一把新 AES key，仅密钥封装用 RSA；无请求签名、无时间戳/nonce → **可无限重放**（重放时用同一 `ek` 即可）。
- 仅靠静态 JWT 做鉴权，任何解包者都能拿到同一凭据；JWT 里 `is_system_admin: true` 且永不过期。
- `HttpLoggingInterceptor` 在 BODY 级别打印已解密的明文（依赖 logcat 泄露面）。

---

## 4. 鉴权凭据

`BuildConfig.CLOUD_API_KEY`（硬编码）：

```
eyJhbGciOiJSUzI1NiIsImtpZCI6IjlkMWRjMzFlLWI0ZDAtNDQ4Yi1hNzZmLWIwY2M2M2Q4MTQ5OCJ9.eyJhdWQiOiJqaW5qaW5nLTBneHZ0dGl2MzEzZGQ4YWEiLCJleHAiOjI1MzQwMjMwMDc5OSwiaWF0IjoxNzY1NTU4NTUzLCJhdF9oYXNoIjoiYTljbF9kZDdFZkNEVGxKVUFKTGJxdyIsInByb2plY3RfaWQiOiJqaW5qaW5nLTBneHZ0dGl2MzEzZGQ4YWEiLCJtZXRhIjp7InBsYXRmb3JtIjoiQXBpS2V5In0sImFkbWluaXN0cmF0b3JfaWQiOiIxODA2NjM0NzA3OTk4ODE0MjEwIiwidXNlcl90eXBlIjoiIiwiY2xpZW50X3R5cGUiOiJjbGllbnRfc2VydmVyIiwiaXNfc3lzdGVtX2FkbWluIjp0cnVlfQ.ipxjRsF6FjkzWANiEwxfFfsGfTdMw2jGr4yrbXEqI7GoQbp8Q8RWl8F8AM6zl9HRM0JtD4JNUtfs9VyQezIf3CA8dXaP2STQJHQKtu5eOgApaXjjgUW2cqlI2r0q5jbKvAF3COP86CV7mzknVZbCkv-AzYqkPnxjo_2w82pUPxe1p3iZU25b8U7x4pXSuDFj6aa7e6G7BAe69N2jBmm72t33AXSBKuO8i7AG3ePm54ma7UzIfCL0pjmjEtMfNHKhBhJZx4wPGYHUH0ejcLcdaQfz3fYMOsZv2Ny3EKR9t0OAmySm3E13TkBZ6Axroyvq57dIq5DOKdOTxh4VuwzhAg
```

JWT payload（本地 base64url 解码，非 REA 证据）：

```json
{"aud":"jinjing-0gxvttiv313dd8aa","exp":253402300799,"iat":1765558553,
 "at_hash":"a9cl_dd7EfCDTlJUAJLbqw","project_id":"jinjing-0gxvttiv313dd8aa",
 "meta":{"platform":"ApiKey"},"administrator_id":"1806634707998814210",
 "user_type":"","client_type":"client_server","is_system_admin":true}
```

`kid = 9d1dc31e-b4d0-448b-a76f-b0cc63d81498`，`exp = 9999-12-31T23:59:59Z`。

---

## 5. 接口清单

端点枚举 `CloudAPIService$CloudEndpoint`，构造参数为 `(key, defaultPath)`。
默认路径（`ev_327417295855608ce27c0b63ec64979535e8eb78ca0ab897d58d6486b3f4f96c`）：

| # | key | 默认路径 | 业务方法 | 请求体（除自动附加的 `ek`） | 响应模型 |
|---|-----|----------|----------|------------------------------|----------|
| 1 | `loginCode` | `/verifyLoginCode202609` | `verifyLoginCode(code, deviceId, deviceName)` | `code`, `device_id`, `device_name`, `platform:"android"` | `LoginCodeVerifyResult` |
| 2 | `validateToken` | `/validateToken202609` | `validateToken(token, deviceId)` | `token`, `device_id` | `TokenValidateResult` |
| 3 | `userInfo` | `/getUserInfo202609` | `getUserInfo(openId)` | `OPENID`, `UNIONID`, `func:"getUserInfo"` | `UserInfoResult` |
| 4 | `labels` | `/qwvsxdvglkj` | `fetchLabelsPage(openId, page)`（页 1、2 合并） | `OPENID`, `page`（int） | `LabelsDataResult` |
| 5 | `comment` | `/getCommentNew202609` | `getCameraDetail(id)` / `addCameraComment(id,nickname,comment)` | `id`, `dowhat:"query"` 或 `dowhat:"addComment"`, `nickname`, `comment` | `CameraDetailResponse` |
| 6 | `planRoute` | `/planRoute202503an202609` | `planRoute(openId,start,end,waypoints,preference,licence)` | `OPENID`, `currentHoldingLicenceValue`, `startPoint{name,address,latitude,longitude}`, `endPoint{...}`, `waypoints[]{...}`, `preferenceValue` | `RoutePlanResult` |
| 7 | `planResult` | `/getPlanResultios202609` | `pollPlanResult(openId, maxRetries)` | `OPENID` | `RoutePlanResult` |
| 8 | `gaode` | `/getgaodeSDK4Android202609` | `getAMapKeys()` | `platform:"android"` | `AMapKeysResult`（高德 Key 下发） |
| 9 | —（常量路径） | `/androidCheckUpdate202609` | `checkAppUpdate()` / `fetchAndCachePathsBlocking()` | `{}`（仅 `ek`） | `AppUpdateResult`（含 `paths` 路径下发表） |

证据：
`ev_233372e7324f6c6ee4f8e67f13e4d2958570f8dd768284a89774f5a435efd788`（USER_INFO）、
`ev_61d15ad33f2e918e4b008099b678bbef3d12224d0344b6477becd452b483dac9`（LABELS）、
`ev_5b00aa35f10250b0dc6ce98775b3ef74349955edc0493196292ad510b51607c6`（LABELS 两页合并）、
`ev_cac74c927b3d4c2fa1d17020cfd59874b854e4f1ddb118930c52e59ab1d1757a`（详情查询）、
`ev_27bbc0fec39aefb94e4f09df44f0de112f8fe3cf82d7c89430253a23087bd764`（发评论）、
`ev_15a29fe0fda9c8ff39906dfb82bc4e45d7f6fc7e8040b7e7c737088e4360851f`（路径规划）、
`ev_915aa97b985ea126b934fad836f800e679463e96987a2177478f3b80203b4cd8`（结果轮询，smali）、
`ev_2a8b6b3cab4d7ddbf27a20b6785cfce0df026b78f27a9eb0a31e7b516d547110`（高德 Key）、
`ev_ff3e61830feb10046f130b8078ab986a2d45781cc406a8206645870ec4a250a7`（更新/下发）、
`ev_4c13b98b49b2f3383bc49742ea081700c13ad4944a8ebb9f68efcdf81636f0d0`（登录码验证）、
`ev_4c62e4ba3a68139d4ec2d556dbfa4e60a2d9c8f7c645b19d498d12298ee01ae0`（Token 校验）、
`ev_bf34725f894e80d6d6287ad39b52322576ee03a068210e7db334967d2cd21773`（路径下发解析）。

### 5.1 响应字段（取自 model 类）

- `LoginCodeVerifyResult`：`success`, `error`, `token`, `userId`, `unionid`, `nickname`, `avatarUrl`, `isVip`, `vipDeadline`, `registrationDate`
- `TokenValidateResult`：`success`, `valid`, `error`, `userId`, `nickname`, `avatarUrl`, `isVip`, `vipDeadline`
- `AppUpdateResult`：`version`, `versionCode`, `forceUpdate`, `updateLog`, `downloadUrl`（另解析未声明的 `paths` 对象）

证据：`ev_f1954a5d64e5bf8f67c049e4916b50ff7b9eaf677c129ee49fb41f6b3c95f216`、
`ev_c816073457776af137f26f8ca3bc7c373ef66e2ff240e4730f8a79c2d1ed8159`、
`ev_2a2ec6e7168620e3416bf6a3b22735b864095ea6fdaa47e052d3666f62641790`。

### 5.2 非业务调用（第三方 SDK，非自建后端）

反编译字符串可见高德地图/LBS SDK 的公开域名（`restsdk.amap.com`、`apilocate.amap.com`、
`m5.amap.com`、`wprd0*.is.autonavi.com` 等）以及阿里云 NLS 语音 `nls-gateway-inner.aliyuncs.com`，
这些属于 SDK 内置流量，与上面的自建接口无关。

---

## 6. 动态端点下发（重要）

`resolvePath(ep)`：优先读取 `SharedPreferences("encrypted_endpoint_paths_v1")` 中的
`path_<key>`，没有则用枚举里的 `defaultPath`。

`fetchAndCachePathsBlocking()`：POST `/androidCheckUpdate202609`（空业务体），解密后取响应的
`paths` 对象，逐项写回 `path_<key>`；同时把整个响应反序列化为 `AppUpdateResult`。

`encryptedPost(endpoint, ...)`：命中 **HTTP 404** 时打印“端点 … 返回 404，刷新下发路径后重试”，
调用一次 `fetchAndCachePathsBlocking()`，若路径变化则用新路径重试一次，否则抛出。

依据：`ev_4b24e0d55aca243d49ce9ae2314ac9e7496f495c297b6d2d5dd323ac3e5f7463`（resolvePath）、
`ev_9cb6c4a4047d6e8789ab67589cef2bdddb6db7577690717b3a9e7440e2bf6779`（404 重试）。

> 实践含义：写死 `/getUserInfo202609` 这类路径会随时失效；正确做法是每次会话先请求一次
> `/androidCheckUpdate202609` 拿 `paths` 映射，或在 404 时刷新。

---

## 7. 业务行为细节

- **路径规划**用独立 OkHttpClient（读超时 10 分钟）。响应含 `FUNCTION_TIME_LIMIT_EXCEEDED`
  或结果为空时，转入 `PLAN_RESULT` 轮询：每 5s 一次，最多 120 次（10 分钟）。
- **评论接口复用**：同一个 `comment` 端点用 `dowhat` 区分 `query`（取详情+评论）与 `addComment`。
- **labels 分页**：固定请求 page=1、page=2，然后把两页 `labelsData` 拼接（`showPoints` 只用第一页）。
- **`getIfOpenAppleMap()` 硬编码返回 `false`**，无网络调用（iOS 逻辑残留）。
- **高德 Key 服务端下发**：`gaode` 端点返回 `AMapKeysResult`，供 `AMapKeyManager` 动态初始化地图 SDK。
- **构建标志**：`BUILD_TYPE="debug"`、`DEV_MODE=true`、`SKIP_LOGIN=false`、`DEBUG=false`（发布版里仍留 debug 标志）。
- **错误面**：非 2xx 时把响应体原文塞进异常信息（`接口 <path> 返回 <code>: <body>`），未加密的错误响应可能直接泄露服务端细节。

---

## 8. 复现一个请求的最小步骤

1. 生成 32B `raw`；用上面的 RSA 公钥做 OAEP-SHA256 得到 `ek`。
2. `POST <BASE><path>`，头 `Content-Type: application/json`、`Authorization: Bearer <JWT>`，
   体为业务 JSON + `ek`。
3. 取响应字节；若全为 Base64 字符则先 Base64 解码。
4. 切 `iv=bytes[0:12]`、`tag=bytes[12:28]`、`ct=bytes[28:]`，AES-256-GCM 解密 `ct||tag`。
5. 对明文做 raw deflate 解压 → JSON。

---

## 9. 证据与局限

- 引擎：`jadx-headless-mcp` 0.7.1，JAR SHA-256 `6e5eacf5…ff75`，bridge `e5044a83…fa30`，
  载入类数 5140、资源 5493（`ev_ed0b2bb894397c3fb5b8c55ee835901758c92fad298057698112a3e422dbc910`）。
- 类/方法清单：`ev_ec68688f4237ffda2e4207940a06116219bf7a97029bd4938308caefed8043b8`（CloudAPIService）、
  `ev_1d91f332c2542ccfd52d054dde31129e28fe9eefc30d999f0158df4b9d27a33f`（CloudEndpoint）、
  `ev_275bba04f883443f0e5dcb536bf027fb2e82986927610286d0747adf7813648e`（DataCrypto）。
- **未观测/未知**：
  - 未运行 App，未验证服务端真实响应结构与状态码（依赖 model 类与调用点推断）。
  - `CloudAPIService.<clinit>`、`DataCrypto.<clinit>` 反编译失败（“could not establish one selected method”），
    相关常量改由字段静态值/`publicKey$2.invoke` 的 smali 常量取得。
  - `pollPlanResult` 与 `publicKey` 初始化回退为 smali，未得到 Java 伪代码。
  - 服务端是否按用户/设备校验、`paths` 是否按版本/用户下发、是否存在风控与频率限制，静态不可知。
  - 未评估 TLS 证书固定（代码里未见到相关配置；`network_security_config.xml` 仅见
    `restapi.amap.com`、`tcloudbaseapp.com` 的 cleartext 白名单——该项由普通脚本解析补充，非 REA 证据）。
  - 反射/动态加载调用的接口未覆盖（静态调用图边界）。

### 证据文件位置（本机临时目录）

```
/private/var/folders/01/b73csgzx3zd0ssfbx14w75540000gn/T/opencode/rea-dmm/
  pkg.json, cloudapi_class.json, cloudendpoint_class.json,
  m_*.json, s_*.json, l_*.json, c_*.json   # 每次调用的完整 REA Evidence
```
