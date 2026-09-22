# 漫画狗 (dogemanga.com) Mihon 扩展

自建 Mihon / Tachiyomi 漫画源，站点：https://dogemanga.com

## 这是什么

一个标准的 Mihon（Tachiyomi）扩展模块，源码在 `src/zh/dogemanga/`。
本仓库不包含上游代码，编译时由 GitHub Actions 拉取 keiyoushi/extensions-source（钉死 commit）后把本模块注入进去编译。

## 为什么不在本地编

keiyoushi 上游 31522 个文件、2755 个 Kotlin 源，Gradle 编译需要 2~4G 内存。
本机 VPS 可用内存只有 ~1.5G，编译必然 OOM 并拖垮同机其它服务，所以改为云端编译。

## 站点结构（本扩展用到的）

| 用途 | 规则 |
| --- | --- |
| 搜索 | `GET /?q=关键词`（HTML，首屏 24 条） |
| 热门 | `GET /`（熱門排行，首屏 24 条） |
| 最新连载 | `GET /?s=1`（最新連載，独立列表，不是热门的复制品） |
| **翻页（关键）** | 站点自己的「加载更多」接口 `GET /_search?...`，返回 JSON `{manga_cards:[HTML片段...], next:"<下一页URL>"}` |
| 热门/最新翻页 | `/_search?p=<令牌>`；令牌由上一页的 JSON `next` 给出，**必须按顺序一页页翻**（跳页拿不到令牌） |
| 搜索翻页 | `/_search?o=<偏移量>&q=<关键词>`，纯偏移，可任意跳页 |
| 令牌藏法 | 首页/搜索页把 `next` 放在 `<script>` 里的 `atob(base64(JSON))`，要先解码 |
| 漫画详情 | `GET /m/<mangaId>`（`span.site-card__manga-title` / `img.site-manga__cover-image` / `h4.text-muted a` / `small.text-muted` 里的連載狀態） |
| 章节 | `GET /p/<chapterId>`，网页里的 `a.site-manga-thumbnail__link`，章节名取 `img.site-manga-thumbnail__image[alt]` |
| 图片 | 章节页 `img.site-reader__image[data-page-image-url]`，整章图片都在 HTML 里 |
| 连载状态 | 卡片/详情页 `small.text-muted`：`連載狀態：連載中` → 连载中，`完结` → 已完结 |
| 防盗链 | 图片裸抓 403（Cloudflare），必须带 `Referer: https://dogemanga.com/`；KeiSource 基类已自动为所有请求带上该头 |

## 编译与发布

推送到 `main` 或手动触发 `Build extension & Mihon store` workflow，CI 会：

1. 拉上游 `keiyoushi/extensions-source`（钉死 commit）→ 注入本模块 → `spotlessApply` 自愈格式 → 编 **release 签名 APK**
2. 校验 APK 证书指纹 == 商店索引里声明的 `signingKey`（不等直接红叉）
3. 校验 R8 压缩没把关键串干掉（源名/baseUrl/接口/选择器/状态词 9 项）
4. 生成商店文件 `index.json` + `apk/` + `icon/`，并推到本仓库根目录（由仓库变量 `STORE_PUBLISH=true` 控制）

## 手机上怎么装（商店版，推荐）

Mihon → **设置 → 浏览 → 扩展仓库 / Extension stores → 添加**，粘贴：

```
https://raw.githubusercontent.com/1468323775/mihon-store/main/index.json
```

之后：浏览 → 扩展 → 出现「漫画狗」→ 装 → **不用点 Trust**。以后这边一改代码，你在那一页点「更新」就升级。

⚠️ 两件事：

- 该扩展被标为 `MIXED`（站点挂成人广告）→ Mihon 里要先开 **设置 → 扩展/高级 → 显示 NSFW 扩展**，否则列表里看不到它
- 手装的旧包跟商店包签名不同，**先卸载旧包**再装商店版，否则报「应用未安装」

不想用商店也行：`apk/` 目录里就是要的 APK，直接下载 sideload，装完 Mihon 会提示 `Untrusted extension`，点一次 Trust。
