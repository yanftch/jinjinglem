# 躲猫猫（duomaomao）云端接口文档 · 实测版

| 项目 | 值 |
|---|---|
| 抓包目标 | `com.duomaomao.duomaomao` v9.0.0 (versionCode 9)，`duomaomao_hook.apk` |
| 设备 | Honor VER-AN00 / Android 14 |
| 实测日期 | 2026-10-28 |
| 服务地址 | `https://jinjing-0gxvttiv313dd8aa-1319848734.ap-shanghai.app.tcloudbase.com` |
| 后端 | 腾讯云 CloudBase 云函数（响应头 `x-cloudbase-upstream-type: Tencent-SCF`） |
| 证据来源 | UI 实跑日志 `_work/api_hook_ui.log` + 脚本探针 `_work/probe/*.json` |
| 复现工具 | `_work/probe/Probe.java`（`java -cp _work/probe/out Probe …`） |

> 本文档只覆盖**只读 / 查询类**接口。`addComment`（写留言）、`verifyLoginCode`（登录换取 token）未做实测，仅在末尾给出从源码读到的结构。
>
> 静态逆向（smali / 模型类 / 加密实现）见同目录 `duomaomao-api-report.md`；本文档是它的**实跑验证版**，字段与行为以本轮抓包和脚本结果为准。

---

## 1. 传输层

### 1.1 通用约定

* 全部接口都是 **POST**，路径由服务端下发（见 §3.1），不要硬编码。
* 请求体：`Content-Type: application/json`，UTF-8，**紧凑 JSON**（无空格）。
* 每个请求体都必须额外带一个字段 **`ek`**（RSA 封装的一次性 AES 密钥，见 1.2），业务字段与 `ek` 平铺在同一层。
* 响应体：`application/octet-stream`，**加密帧**。服务端当前返回二进制；客户端同时兼容 Base64 文本，解析时自动识别。
* 响应头固定带 `x-crypto-version: v1`。

### 1.2 加密信封

**请求侧**

```
AESKey   = 32 字节随机数（AES-256）
ek       = Base64( RSA-OAEP(SHA-256, MGF1-SHA256) (服务端公钥, AESKey) )
            → 344 字符，以 "==" 结尾
请求体   = { ...业务字段..., "ek": "<ek>" }
```

服务端公钥（X.509/SPKI，Base64，294 字节 DER，SHA-256 指纹
`cc04688b751876c5bcc2b4843ad994fb874818cb1d1a7da46e461a78c9bab165`）存放于
`_work/pubkey.txt`，源码常量在
`duomaomao_source/app/src/main/java/com/duomaomao/duomaomao/service/DataCrypto.java:44`。

**响应侧**

```
frame      = HTTP body（二进制，或纯 Base64 文本，客户端两种都认）
IV         = frame[0:12]
tag        = frame[12:28]          (GCM tag, 128 bit)
ciphertext = frame[28:]
plain      = raw-inflate( AES-256-GCM-Decrypt(key=AESKey, iv=IV, tag=tag, ct) )
```

即：先 AES-GCM 解密，再 **raw deflate**（`Inflater(true)`）解压，最后得到 UTF-8 JSON 文本。
实测一帧：`/getCommentNew202609` 响应 `306` 字节，前 12 字节 `58008d9e2cf1c2cb75452abe`
为 IV，`12..28` 为 tag。

**要点**

* `ek` 是一次性的：每个请求新生成 32 字节 AES key，服务端只用它加密本次响应，**没有会话/握手缓存**。
* 服务端对 `ek` 的 Base64 **填充不敏感**（带 `==` 和不带填充都接受）。
* `ek` 缺失 → `400 {"error":"ENCRYPTION_REQUIRED"}`；`ek` 无法解开 → `400 {"error":"HANDSHAKE_FAILED"}`。

### 1.3 请求 Header

UI 实际发出（`CloudAPIService.createRequest`）：

```http
POST /<path> HTTP/1.1
Content-Type: application/json
Authorization: Bearer <静态 JWT>
Cache-Control: no-cache
Cache-Control: no-cache, no-store, must-revalidate
Pragma: no-cache
```

* 没有显式 `User-Agent`（OkHttp 默认；服务端不校验，脚本不带 UA 也能 1:1 复现）。
* `Cache-Control` 出现两次是 OkHttp `FORCE_NETWORK` + 手写 header 叠加的结果，服务端不关心。
* `Content-Type` 实际也不是强校验项：带合法 `ek`、不带 `Content-Type` 同样能通。

### 1.4 鉴权

`Authorization: Bearer <JWT>` 是打包进 APK 的 **CloudBase ApiKey**（不是用户登录态）。

JWT 解码结果（签名已省略，原文见 `_work/apikey.txt`）：

```json
{"alg":"RS256","kid":"9d1dc31e-b4d0-448b-a76f-b0cc63d81498"}
{
  "aud": "jinjing-0gxvttiv313dd8aa",
  "project_id": "jinjing-0gxvttiv313dd8aa",
  "meta": {"platform": "ApiKey"},
  "client_type": "client_server",
  "is_system_admin": true,
  "administrator_id": 1806634707998814210,
  "iat": 1765558553,
  "exp": 253402300799
}
```

* `exp = 253402300799` → **9999-12-31，等于永不过期**；`is_system_admin: true`。
* 也就是说：**这个 Bearer 是项目级管理密钥，任何拿到 APK 的人都能直接调用全部云函数**，与用户账号无关。
* 用户身份完全由请求体里的 `OPENID` 决定，而服务端对 `OPENID` 基本不做校验（见 §5）。

鉴权失败面（网关层，明文 JSON，HTTP 401）：

| 场景 | HTTP | body |
|---|---|---|
| 不带 `Authorization` | 401 | `{"code":"MISSING_CREDENTIALS", ...}` |
| Bearer 错误 | 401 | `{"code":"INVALID_CREDENTIALS", ...}` |
| 带合法 Bearer 但无 `ek` | 400 | `{"error":"ENCRYPTION_REQUIRED"}` |
| `ek` 解不开 | 400 | `{"error":"HANDSHAKE_FAILED"}` |

鉴权在解密**之前**校验（无 Bearer 时优先返回 401，而不是加密错误）。

### 1.5 成功 / 失败判定

* HTTP 200 + 可解密 JSON = 业务成功，**业务层的失败也走 200**（如 `{"success":true,"valid":false}`、`isSuccessOnPlanRoute:false`、`{"error":"UNKNOWN_FUNC"}`）。
* 云函数内部抛异常 → HTTP **400** + 明文
  `{"code":"FUNCTIONS_INVOCATION_FAILED","message":"Function code exception caught (<uuid>)…"}`。
  这是**服务端未捕获异常**，不是参数校验，body 里没有字段级提示。

### 1.6 响应头（实测样本）

```http
content-type: application/octet-stream
server: tcbgw
x-cloudbase-request-id: 21e21b79-3900-4013-ab48-6c03bcd071f5
x-cloudbase-upstream-status-code: 200
x-cloudbase-upstream-timecost: 42770        # 云函数耗时，单位 ms
x-cloudbase-upstream-type: Tencent-SCF
x-crypto-version: v1
x-request-id: 21e21b79-3900-4013-ab48-6c03bcd071f5
x-tencent-scf-request-id: 58b4dfd8-d2e2-11f1-b49e-5254005cf213
date: Wed, 28 Oct 2026 15:15:44 GMT
x-cache-lookup: Cache Miss                  # 出现两次
last-modified: Wed, 28 Oct 2026 15:15:01 GMT
age: 0
content-length: <加密帧长度>
x-nws-log-uuid: 2662971898776346047
```

`x-cloudbase-upstream-timecost` 是判断「服务端到底跑了多久」最可靠的指标（客户端总耗时含 TLS）。

---

## 2. 端点清单

路径由 `/androidCheckUpdate202609` 下发，客户端会缓存到 `SharedPreferences`（`encrypted_endpoint_paths_v1`），命中 404 时自动刷新一次。

| key | path | 用途 | 类型 | 实测 |
|---|---|---|---|---|
| `labels` | `/qwvsxdvglkj` | 全量探头坐标 | 只读 | ✅ |
| `gaode` | `/getgaodeSDK4Android202609` | 下发高德 Key | 只读 | ✅ |
| `planRoute` | `/planRoute202503an202609` | 路线规划 | 只读（消耗额度） | ✅ |
| `planResult` | `/getPlanResultios202609` | 取规划结果（轮询兜底） | 只读 | ✅ |
| `comment` | `/getCommentNew202609` | 探头留言查询 | 只读 | ✅ |
| `userInfo` | `/getUserInfo202609` | 用户信息 / VIP | 只读（会建号） | ✅ |
| `validateToken` | `/validateToken202609` | 登录 token 校验 | 只读 | ✅ |
| `loginCode` | `/verifyLoginCode202609` | 动态码换 token | **写**（未实测） | ⛔ |
| — | `/androidCheckUpdate202609` | 版本 + 路径下发 | 只读 | ✅ |
| — | `addComment`（走 `comment` 路径） | 发留言 | **写**（未实测） | ⛔ |

---

## 3. 端点详情

### 3.1 `POST /androidCheckUpdate202609`

客户端启动时调用，空业务体（只有 `ek`）。

**请求**

```json
{"ek":"<344 chars>"}
```

**响应**

```json
{
  "version": "9.0.0",
  "versionCode": 9,
  "downloadUrl": "https://jinjing-0gxvttiv313dd8aa-1319848734.tcloudbaseapp.com/apk/duomaomao.apk",
  "forceUpdate": true,
  "updateLog": "1. 优化用户体验\n2. 修复已知问题",
  "paths": {
    "labels": "/qwvsxdvglkj",
    "gaode": "/getgaodeSDK4Android202609",
    "planRoute": "/planRoute202503an202609",
    "planResult": "/getPlanResultios202609",
    "comment": "/getCommentNew202609",
    "loginCode": "/verifyLoginCode202609",
    "userInfo": "/getUserInfo202609",
    "validateToken": "/validateToken202609"
  }
}
```

> `paths` 是**服务端可随时改路由**的开关；写脚本时建议先拉一次这个接口，不要写死 path。

---

### 3.2 `POST /qwvsxdvglkj`（labels）

全量探头列表。**这是流量最大的接口**（约 743 KB 明文 / 5000 条）。

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `OPENID` | string | 否 | 实测不校验，可省略 |
| `page` | int | 否 | 实测只有 1 / 2 两个有效值，`≥3` 回落第 1 页 |

```json
{"OPENID":"test_android_vip_user","page":1,"ek":"..."}
```

**响应**

```json
{
  "LabelsData": [
    {
      "name": "昌平区 北清路与辛庄桥交口西 西向东",
      "position": [116.297094, 40.094648],
      "aa": "w",
      "time": "2026-10-26",
      "edittime": "",
      "href": "/content/?80905.html",
      "id": 7429
    }
  ],
  "showPoints": [ /* 最新 10 条，字段同 LabelsData */ ]
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | int | 探头主键，`comment` 接口用它取详情 |
| `name` | string | 探头名，含方向；`【六环外】`/`【二环内】`/`已废弃` 是客户端分类依据 |
| `position` | `[lng, lat]` | **经度在前、纬度在后**，GCJ-02 |
| `time` | `YYYY-MM-DD` | 发现/生效日期 |
| `edittime` | string | 编辑时间，多数为空（5000 条里仅 19 条非空） |
| `href` | string | 官网详情页相对路径 |
| `aa` | string | 历史分类码，取值 `w/1/2/4/5/6/a`；客户端只透传，不参与业务判断 |

**实测分页行为**

| page | 返回条数 | id 范围 | 说明 |
|---|---|---|---|
| 1 | 5000 | 7429 → 2002 | 第一页 |
| 2 | 2326 | 2001 → 1，另有 142 条 id>7000 | 第二页（含少量近期编辑记录） |
| ≥3 | 5000 | 同 page=1 | 越界回落 |

两页合计 **7322** 条唯一记录（5000 + 2326 = 7326，其中 id 6657、6668 两页重复）。

`aa` 实测分布（page=1）：`2`=3426、`6`=803、`w`=636、`1`=126、`5`=4、`4`=3、`a`=2。
`6` 与名字里的「六环外」高度相关（1039 条含「六环」的记录中 801 条 `aa=6`），但**不是**严格映射，分类逻辑在客户端按 `name` 子串判定。

---

### 3.3 `POST /getgaodeSDK4Android202609`

下发明文高德 Key（SDK Key + Web 服务 Key）。

**请求**：`platform` 可选，省略也返回同样结果。

```json
{"platform":"android","ek":"..."}
```

**响应**

```json
{
  "success": true,
  "keys": ["1d41fd83…", "a7f974b2…", "c454dddc…", "fc3565fc…", "3baa8bd1…", "83915a12…", "ee33c247…"],
  "count": 7,
  "webServiceKeys": ["d08b0c62874965b9c1bb0563d04ffa62", "76e6c230f09d7f7a748fe3def1453241"],
  "webServiceCount": 2,
  "timestamp": 1793201003928
}
```

* `webServiceKeys[0]` 即高德 Web 服务 key，可直接用于 `restapi.amap.com/v3/place/text`（实测 HTTP 200，命中 800 条）。
* `keys` 是 Android SDK key，客户端按顺序轮换使用。

---

### 3.4 `POST /getUserInfo202609`

**请求**

| 字段 | 必填 | 说明 |
|---|---|---|
| `func` | **是** | 固定 `"getUserInfo"`，缺省返回 `{"error":"UNKNOWN_FUNC"}` |
| `OPENID` | 否 | 缺省/空串 → `temp_undefined` 临时用户 |
| `UNIONID` | 否 | 实测可省略 |

```json
{"OPENID":"test_android_vip_user","func":"getUserInfo","UNIONID":"test_android_vip_user","ek":"..."}
```

**响应**

```json
{"userId":"48e0ec25698211e200b765464eb45e8a","isVIP":false,"VIPDeadline":"2026-02-05T15:18:58.360Z","isNew":false,"buy_times":0}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `userId` | string | 服务端内部用户 id（≠ OPENID） |
| `isVIP` | bool | 服务端认定的会员状态 |
| `VIPDeadline` | ISO8601 | 会员到期时间 |
| `isNew` | bool | 是否首次建档（仅第一次调用为 true） |
| `buy_times` | int | 购买次数 |
| `isTemp` | bool | 仅匿名/空 OPENID 时出现，值为 true |

**实测三种账号**

| 输入 | 结果 |
|---|---|
| `test_android_vip_user` | `isVIP:false`，到期 `2026-02-05`，`isNew:false` |
| 全新随机 OPENID | `isVIP:true`，到期 = 当前 +2 天，`isNew:true`（第二次变 false） |
| 空 OPENID | `userId:"temp_undefined"`，`isVIP:true`，到期 = 当前 +1 天，`isTemp:true` |

> 结论：**服务端会为任意 OPENID 自动建档并发放 1～2 天试用 VIP**，不是「查不到就报错」。
> 而 `test_android_vip_user` 是过期账号（`isVIP:false`），正好可以作为「非会员」对照。

---

### 3.5 `POST /getCommentNew202609`（comment）

探头详情 + 留言列表。`dowhat` 决定动作，本文档只测 `query`。

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | int | 否* | 探头 id（来自 labels）；**不存在的 id 不报错** |
| `dowhat` | string | **是** | `"query"` 查询；缺省返回 `{"error":"UNKNOWN_ACTION"}` |

```json
{"id":752,"dowhat":"query","ek":"..."}
```

**响应**

```json
{
  "cameraName": "西城区 安德路鼓楼外大街路口北 北向南",
  "creatTime": "2020-07-28",
  "position": [116.395347, 39.951861],
  "comments": [
    {"nickname": "古槐听讼  ", "time": "2021/3/12 17:14:16", "content": "我在这里因为没有办进京证被拍过"},
    {"nickname": "张  ", "time": "2020/8/19 19:36:27", "content": "这里会拍吗？"}
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `cameraName` | string | 探头名；id 不存在时为空串 |
| `creatTime` | string | 探头建档日期（注意拼写是 `creatTime`） |
| `position` | `[lng,lat]` | id 不存在时为 `[]` |
| `comments[].nickname` | string | 昵称，可能带尾随空格 |
| `comments[].time` | string | `YYYY/M/D HH:mm:ss` |
| `comments[].content` | string | 留言正文 |

**重要实测：不存在的 id 会「兜底」返回全站最新 7 条留言**

`id = 0 / -1 / 999999 / 999999999 / 缺省` 五种情况返回**完全相同**的 545 字节响应：

```
cameraName:""  creatTime:""  position:[]
comments: 7 条全站最新留言
```

这就是 App 里会在任意探头下看到「澳门赌场线上开户」这类广告的原因——**广告挂在全局兜底查询上，而不是某个探头**。服务端没有做「id 不存在 → 空列表」的区分。

---

### 3.6 `POST /planRoute202503an202609`（planRoute）

核心接口。请求经高德路径规划后再加工，**耗时 1 s ～ 43 s**（外省终点最慢）。

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `startPoint` | object | **是** | 缺省 → HTTP 400 |
| `endPoint` | object | **是** | 同上 |
| `waypoints` | array | **是** | 缺省 → HTTP 400；`[]` 表示直达；实测可传 6 个（UI 只允许 5 个） |
| `preferenceValue` | int | 否 | 缺省 → HTTP 200 但 body 为字面量 `null`（服务端 bug） |
| `currentHoldingLicenceValue` | int | 否 | 缺省等价于 `3`（实测响应与 `3` 逐字节相同） |
| `OPENID` | string | 否 | 实测不参与计算，也不校验 |

点对象：

```json
{"name":"北京南站","address":"北京南站","latitude":39.865,"longitude":116.378}
```

* `latitude` / `longitude` 为 **double 十进制**（不是字符串，和响应里的字符串相反）。
* 纬度/经度越界（如 999）→ HTTP 400 `FUNCTIONS_INVOCATION_FAILED`。
* `name` / `address` 只做展示，传「地图选点」也能规划。

**完整请求示例**

```json
{
  "OPENID": "test_android_vip_user",
  "currentHoldingLicenceValue": 3,
  "preferenceValue": 1,
  "startPoint": {"name":"北京南站","address":"北京南站","latitude":39.865,"longitude":116.378},
  "endPoint":   {"name":"首都机场T3","address":"首都机场T3","latitude":40.075,"longitude":116.61},
  "waypoints": [],
  "ek": "..."
}
```

**响应（单段 / waypoints 为空）**

```json
{
  "isSuccessOnPlanRoute": true,
  "routePlanResponse": [
    {"longitude":"116.377665","latitude":"39.864845"},
    {"longitude":"116.377572","latitude":"39.864964"}
  ],
  "navigationSteps": [
    {
      "instruction": "向西北行驶15米左转",
      "orientation": "西北",
      "distance": "15",
      "tolls": "0",
      "toll_distance": "0",
      "toll_road": [],
      "duration": "5",
      "polyline": "116.377665,39.864845;116.377572,39.864964",
      "action": "左转",
      "assistant_action": [],
      "tmcs": [
        {"lcode": [], "distance": "15", "status": "畅通",
         "polyline": "116.377665,39.864845;116.377572,39.864964"}
      ],
      "cities": [
        {"name": "北京城区", "citycode": "010", "adcode": "110100",
         "districts": [{"name": "丰台区", "adcode": "110106"}]}
      ]
    }
  ],
  "distance": "56.7km",
  "useTime": "1小时6分钟",
  "canUseBaiDu": null,
  "IOSURI": "",
  "IOSURI2": ""
}
```

**响应（waypoints 非空）**

`routePlanResponse` 变成**二维数组**（按段分包），并多出 `isSuccessOnPlanRouteForEvery`：

```json
{
  "isSuccessOnPlanRoute": true,
  "routePlanResponse": [[{...}], [{...}], [{...}]],   // 3 段
  "navigationSteps": [ /* 49 条，全段合并 */ ],
  "distance": "68.2km",
  "useTime": "1小时29分钟",
  "isSuccessOnPlanRouteForEvery": [true, true, true],
  "canUseBaiDu": null, "IOSURI": null, "IOSURI2": null
}
```

**字段语义**

| 字段 | 类型 | 说明 |
|---|---|---|
| `isSuccessOnPlanRoute` | bool | **「当前证件条件下是否存在可行路线」**，与 VIP 无关。`false` 时仍会返回完整几何（用于前端展示「不可行」路线） |
| `routePlanResponse` | array | 路径点，`{longitude, latitude}` **字符串**；单段是一维，多段是二维 |
| `navigationSteps` | array | 导航步骤，见上；`polyline` 是 `lng,lat;lng,lat` 字符串 |
| `distance` | string | 成功时如 `"49.6km"`；失败恒为 `"0m"` |
| `useTime` | string | 成功时如 `"1小时7分钟"`；失败恒为 `""` |
| `isSuccessOnPlanRouteForEvery` | bool[] | 仅多段时出现，逐段成功标记 |
| `canUseBaiDu` | null | 实测恒 null（`getIfOpenAppleMap` 也恒 false，功能被关死） |
| `IOSURI` / `IOSURI2` | string/null | iOS 唤起链接，Android 侧为空 |

**实测的「false 但几何完整」长什么样**（2026-10-29，昌平北清路 `116.297094,40.094648` → 国贸桥
`116.461922,39.903191`，`licence=1`、`preference=1`）：

```
isSuccessOnPlanRoute = false
distance = "0m"          useTime = ""
routePlanResponse    = 1 段 / 604 个坐标点（完整、从起点到终点）
navigationSteps      = 20 段，全部带 instruction / road / distance / duration
```

把 20 段的 `distance`（米）和 `duration`（秒）分别累加，得到 **31.7 km / 71 分** —— 也就是真实路线。
所以「不可行」只是证件条件判定，**不能拿顶层 `distance`/`useTime` 当作路线是否存在的判据**；
要多段数据就按 `navigationSteps` 汇总，或者把 `licence` 换成 3 让 `isSuccessOnPlanRoute` 变 true。

**枚举（源码 `RoutePlanFragment`）**

`currentHoldingLicenceValue`：

| 值 | UI 文案 |
|---|---|
| 1 | 无任何证件 |
| 2 | 六环外进京证 |
| 3 | 六环内进京证 |

`preferenceValue`：

| 值 | UI 文案 |
|---|---|
| 1 | 速度最快 |
| 2 | 距离最短 |
| 3 | 不走高速 |

**实测矩阵**（北京南站 39.865,116.378 → 首都机场 T3 40.075,116.610）

| licence | pref | isSuccess | distance | useTime | 点数 / 步数 | 耗时 |
|---|---|---|---|---|---|---|
| 1 | 1 | false | 0m | "" | 687 / 21 | 22.6 s |
| 1 | 2 | false | 0m | "" | 693 / 21 | 19.3 s |
| 2 | 1 | false | 0m | "" | 687 / 21 | 19.1 s |
| 3 | 1 | **true** | 56.7 km | 1小时6分钟 | 687 / 21 | 1.1 s |
| 3 | 2 | **true** | 49.6 km | 1小时7分钟 | 653 / 23 | 1.4 s |
| 3 | 3 | **true** | 49.7 km | 1小时12分钟 | 672 / 21 | 0.9 s |

规律：

* 成功/失败由 **进京证 + 终点是否在限行区** 决定，`preference` 只影响选路。
* 失败路径固定耗时 ~19–22 s（服务端跑完才判定不可行）；成功且命中缓存时 **~1 s**，未命中时首次可达 20–43 s。
* 同一组参数重复请求，结果**逐字节一致**（服务端有结果缓存）。

---

### 3.7 `POST /getPlanResultios202609`（planResult）

`planRoute` 的轮询兜底：当 `planRoute` 返回 `FUNCTION_TIME_LIMIT_EXCEEDED` 或空结果时，客户端每 5 s 调一次，最多 120 次（源码 `PLAN_ROUTE_POLL_INTERVAL_MS=5000`、`MAX_RETRIES=120`）。

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `OPENID` | string | 否 | 用它去取「该用户最后一次规划结果」 |

```json
{"OPENID":"test_android_vip_user","ek":"..."}
```

**响应**

有缓存结果：

```json
{"isSuccess": true, "result": { /* 与 planRoute 响应完全同构 */ }}
```

无结果 / 未知 OPENID：

```json
{"isSuccess": false}
```

**实测**

* `test_android_vip_user` → 返回最后一次 `planRoute`（pref=3，49.7 km，672 点），**说明按 OPENID 存了最近一次结果**。
* `u_probe_no_such_user` → 返回它自己最后一次（92049 字节的失败路线）。
* 全新 OPENID / 空 OPENID → `{"isSuccess":false}`。

> 注意：`result` 里没有 OPENID 回显，用不同 OPENID 查询会拿到**各自**的缓存，容易误判。测试时务必用独立 OPENID。

---

### 3.8 `POST /validateToken202609`

校验用户登录 token（不是 §1.4 的 Bearer）。

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `token` | string | 是 | 登录 token |
| `device_id` | string | 是 | 设备 id |

```json
{"token":"TEST-VIP-001","device_id":"probe-device","ek":"..."}
```

**响应**

```json
{"success":true,"valid":false,"error":"登录已失效，请重新登录"}
```

* **HTTP 200 + `success:true`**，真正的结果看 `valid`。
* `TEST-VIP-001` 与随便伪造的 JWT 都返回同一结果，说明 token 是服务端签发的会话串，不是可预测的固定值；`TEST-VIP-001` **不是**有效测试凭证。
* 未观察到频率限制或封禁（本次共调用 2 次）。

---

### 3.9 `POST /verifyLoginCode202609`（未实测）

源码结构（`CloudAPIService.verifyLoginCode`）：

```json
{"code":"<动态码>","device_id":"<id>","device_name":"<name>","platform":"android"}
```

响应反序列化为 `LoginCodeVerifyResult`，应含 token 字段。**属于登录写操作，本次未调用。**

---

### 3.10 `addComment`（未实测）

与 `comment` 同一路径，用 `dowhat` 区分（源码 `addCameraComment(id, nickname, comment)`，推测 `dowhat:"add"`）。**写接口，本次未调用。**

---

## 4. 错误面汇总

| 现象 | HTTP | body | 触发条件 |
|---|---|---|---|
| 缺凭证 | 401 | `{"code":"MISSING_CREDENTIALS"}` | 无 Authorization |
| 凭证错 | 401 | `{"code":"INVALID_CREDENTIALS"}` | Bearer 无效 |
| 未加密 | 400 | `{"error":"ENCRYPTION_REQUIRED"}` | 无 `ek` |
| 握手失败 | 400 | `{"error":"HANDSHAKE_FAILED"}` | `ek` 非法/解不开 |
| 函数异常 | 400 | `{"code":"FUNCTIONS_INVOCATION_FAILED","message":"Function code exception caught (uuid)…"}` | 缺 `startPoint`/`endPoint`/`waypoints`、坐标越界、类型错误 |
| 未知动作 | 200 | `{"error":"UNKNOWN_ACTION"}` | comment 缺 `dowhat` |
| 未知功能 | 200 | `{"error":"UNKNOWN_FUNC"}` | userInfo 缺 `func` |
| 业务失败 | 200 | `{"success":true,"valid":false,…}` / `isSuccessOnPlanRoute:false` | 正常业务分支 |
| 空响应 | 200 | 字面量 `null` | planRoute 缺 `preferenceValue` |

---

## 5. 实测结论（与「VIP 改造」直接相关）

1. **服务端根本不校验 VIP。**
   * 用已过期账号 `test_android_vip_user`（`isVIP:false`）调 `planRoute`，与任意随机 OPENID 的响应**逐字节相同**（92021 字节）。
   * `OPENID` 字段甚至可以整个删掉，结果不变。
   * 所以「VIP 才能规划路线」是**纯客户端限制**（`RoutePlanFragment` 里 `isVIP().getValue() != true` 就 return），hook 把 `UserInfo.isVIP()` 改成恒 true 就绕过了，服务端无感。

2. **`isSuccessOnPlanRoute:false` 不是「VIP 失败」，是「证件条件不可行」。**
   * UI 显示「第1段规划结果：规划失败」的真实原因是当时 `currentHoldingLicenceValue=1`（无任何证件）+ 终点在六环内。
   * 改成 `3`（六环内进京证）立刻 true，56.7 km / 1小时6分钟。
   * 这也解释了 App 里那句提示「如果想躲避早晚高峰，选择『无任何证件』即可全量躲避」——证件枚举直接改变服务端选路约束。

3. **服务端给每个新 OPENID 自动发试用 VIP。**
   * 随机 OPENID 首次 `getUserInfo` → `isVIP:true`，到期 +2 天；空 OPENID → `temp_undefined`，+1 天。
   * 这意味着即使不改客户端，换一个 OPENID 也能拿到「会员」身份——反过来说明会员体系没有服务端强约束。

4. **`ek` 是一次性密钥，重放无效但协议可 100% 复现。**
   * UI 抓到的 body 去掉 `ek` 后喂脚本，返回 18890 字节，106 个路径点、10 条导航步骤与 UI **完全相等**。
   * 说明请求体业务字段、加密信封、响应解析都已对齐；`ek` 必须每次新生成。

5. **`comment` 的全局兜底是被低估的问题。**
   * 任意不存在的 id 都返回同一批「全站最新 7 条留言」，广告因此可以出现在所有探头下。
   * 这不是客户端 bug，是服务端查询没有 `WHERE id = ?` 命中时的兜底分支。

6. **`labels` 一次拉全量 7322 条，没有增量接口。**
   * 冷启动 ~740 KB，`page` 只有 1/2 两态，`≥3` 回落第一页。
   * 对抓包/复现来说这是最省事的入口：一次拿到全部 id 与坐标。

---

## 6. 复现方式

```bash
cd /Users/yanfeng/Documents/workspace/opencode

# 编译（只需一次）
javac -d _work/probe/out \
  java-cloud-api-client/src/main/java/com/example/cloudapi/{EncryptedCloudApiClient,JsonCodec,CloudApiException}.java \
  _work/probe/Probe.java

# 1) 原样回放 UI 抓到的 body（去掉 ek）
java -cp _work/probe/out Probe replay _work/probe/ui_planroute_body.json

# 2) 单点规划
java -cp _work/probe/out Probe planroute <openid> <sLat> <sLng> <sName> <eLat> <eLng> <eName> <licence> <pref>

# 3) 其它只读接口
java -cp _work/probe/out Probe planresult <openid>
java -cp _work/probe/out Probe comment <id>
java -cp _work/probe/out Probe userinfo <openid>
java -cp _work/probe/out Probe labels <openid> <page>
java -cp _work/probe/out Probe gaode
java -cp _work/probe/out Probe validate <token> [deviceId]

# 4) 任意 path + 任意 body（实验用）
java -cp _work/probe/out Probe raw /planRoute202503an202609 '{"...":"..."}'

# 5) 看服务端原始响应字节（确认加密帧格式）
java -cp _work/probe/out Probe dump /getCommentNew202609 '{"id":752,"dowhat":"query"}'
```

`Probe` 从 `_work/apikey.txt`（Bearer）和 `_work/pubkey.txt`（RSA 公钥）读取凭证，不联网取配置。

---

## 7. 风险与限制

* **凭证**：`_work/apikey.txt` 是项目级管理 JWT（`is_system_admin:true`，exp=9999），`_work/pubkey.txt` 是配套公钥。二者都来自 APK，属于敏感材料，不要提交到公开仓库。
* **额度**：`planRoute` 会真实调用高德路径规划，单次 1–43 s；失败路径也会跑满 ~20 s。测试时优先用「六环内进京证 + 短途」以命中缓存。
* **写接口**：`addComment`、`verifyLoginCode` 未实测，结构与字段来自反编译源码，可能存在偏差。
* **缓存**：`planRoute` 对相同参数有服务端缓存，重复实验会失真；需要「干净」结果时请换坐标或换 OPENID。
* **路径会变**：所有 path 都可能被 `/androidCheckUpdate202609` 改掉，长期脚本应每次启动先拉一次。
* **未覆盖**：`x-cloudbase-upstream-timecost` 的缓存命中判定、`comment` 的分页/条数上限、`planRoute` 的并发限流，本次均未做压力测试。

---

## 8. Spring Boot 封装

上面所有只读端点已经封装成一个本地 REST 服务：[`duomaomao-spring/`](duomaomao-spring/README.md)。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
MVN=~/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn

cd duomaomao-spring
"$MVN" -B test                                  # 22 个离线测试
"$MVN" -B -DskipTests package
DUOMAOMAO_BEARER_TOKEN_FILE=$PWD/../_work/apikey.txt PORT=8080 \
  "$JAVA_HOME/bin/java" -jar target/duomaomao-api-server-0.1.0.jar
```

| 本服务 | 本文档对应端点 |
|---|---|
| `GET /api/v1/update`、`GET /api/v1/paths` | `/androidCheckUpdate202609` |
| `GET /api/v1/labels?page=1\|2`、`GET /api/v1/cameras/{id}` | `/qwvsxdvglkj` |
| `GET /api/v1/gaode-keys` | `/getgaodeSDK4Android202609` |
| `GET /api/v1/users?openid=` | `/getUserInfo202609` |
| `GET /api/v1/comments/{id}` | `/getCommentNew202609`（`dowhat=query`） |
| `POST /api/v1/tokens/validate` | `/validateToken202609` |
| `POST /api/v1/routes/plan` | `/planRoute202503an202609` |
| `GET /api/v1/routes/result?openid=` | `/getPlanResultios202609` |

`loginCode` / `addComment` 两个写接口仍然不实现。

封装过程中补到本文档的三个实测细节：

1. **`navigationSteps` 形状不稳定**：同一次响应里 `action`、`assistant_action` 大多数是字符串、
   个别段是 `[]`；`toll_road` 大多数是数组、个别段是字符串。强类型解码会整条失败，
   封装层用 `JsonNode` 原样透出。
2. **`labels?page=2` 的 `showPoints` 是 `null`**，只有 `page=1` 才带 10 条最新点位。
3. **`routePlanResponse` 单段是一维、多段是二维**，封装层统一归一化成二维，调用方不必判断。


### 8.1 全量导出与前端页面

`duomaomao-spring` 还带了一个操作页（`GET /`）和一组导出接口，把「全量探头 + 每个探头的留言详情」
抓成一个固定 JSON 文件：

| 接口 | 说明 |
|---|---|
| `POST /api/v1/export/start?limit=&detail=` | 起后台任务（异步，202；已在跑返回 409） |
| `GET /api/v1/export/status` | 进度、失败数、落盘路径与大小 |
| `GET /api/v1/export/preview?page=&size=&q=` | 分页摘要（不含留言正文） |
| `GET /api/v1/export/cameras/{id}` | 单条完整详情 |
| `GET /api/v1/export/file` | 下载落盘文件 |

导出时踩到的两个新事实：

1. **`comment` 会对个别合法 id 直接 400**。例：`id=7417` 每次必现
   `FUNCTIONS_INVOCATION_FAILED (Function code exception caught <uuid>)`，
   重试无效；其它 id 正常。所以批量抓取必须按条容错，不能一条失败就整体中断。
2. **`comment` 对不存在的 id 返回的是「全站最新 7 条留言」**（`cameraName=""`）。
   批量导出必须丢掉这种记录，否则会把别人的留言写进任意探头的详情里。

全量实测（2026-10-29）：7322 条探头、7322 次 `comment` 请求、8 并发 **4 分 21 秒**、
落盘 **11 MB**、留言 77353 条；详情失败 **1 条**（即上面的 7417）。
未加重试时同参数失败 56 条（0.76%），失败 id 呈连续区间（一次 CDN 抖动带走一整批在飞请求），
重试一次即可消除。
