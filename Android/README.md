# 进京了 Android

原生 Android 客户端，使用 Kotlin + XML（无 Compose），直接访问现有 Java 后端。用 Android Studio 打开本目录 `Android/`，等待 Gradle 同步后运行 `app`。默认服务地址为 `https://jinjingle.yixiangshe.com`；需要切换开发服务时修改 `app/build.gradle.kts` 的 `API_BASE_URL`，并仅在本地使用 HTTPS 测试地址。

## 已迁移页面

| 小程序 | Android | 接口/行为 |
| --- | --- | --- |
| 首页地图 | `MainActivity` | 按可视范围加载探头、搜索并缩放到结果、定位、最近新增弹窗 |
| 探头详情/评论 | `CameraActivity` | 详情、图片、评论列表、发表评论 |
| 路线规划 | `RouteActivity`、`PlacePickerActivity` | 起终点/途经点选取与搜索、证件/偏好、双接口并行请求、`PENDING` 轮询、三分钟截止、路线折线与步骤对比 |
| 我的 | `ProfileActivity` | 昵称、会员、路线、上报、限行说明、帮助/关于 |
| 会员 | `MemberActivity` | 小程序动态码登录、会员权益和订单查询、退出登录 |
| 上报探头 | `ReportActivity` | 昵称、文字、选填截图及 1MB 校验、审核前上报 |
| H5 容器 | `WebPageActivity` | HTTPS 网页加载，限行说明入口 |

小程序里标为“暂不可用”的进京证、实时导航在 Android 也保留相同提示。Android 支付暂未开放，原因和需要确认的事项见[待确认事项.md](待确认事项.md)。

## 配置地图 Key

高德 Android SDK 需要**Android 平台 Key**，网页 JS Key 和 Web 服务 Key 均不能代替。开发时在个人 `~/.gradle/gradle.properties` 中加入：

```properties
amapAndroidKey=你的Android平台Key
```

当前包名为 `cn.jinjingle.android`。高德控制台还需绑定此包名和应用签名 SHA-1；未配置时项目仍能编译，地图/选点搜索无法正常显示。发布签名和调试签名的 SHA-1 通常不同。不要把 Key、签名文件或 `local.properties` 提交到仓库。

## 构建

```bash
cd Android
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。需本机 Android SDK 34 和 JDK 17。动态码须先在小程序“会员服务”生成，10 分钟内在 Android“会员”页面输入；后端现有 `/api/v1/members/app-login` 会复用同一会员账号。地图公开查询不要求登录；路线规划仍由后端校验会员权益。
