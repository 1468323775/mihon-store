# 发布 Mihon 插件商店 —— 已上线

当前状态：**已上线（2026-09-22）**。仓库 `1468323775/mihon-store` 已设为公开，它既是源码仓库也是商店本体：
CI 编完 release 包后把 `index.json` + `apk/` + `icon/` 提交回本仓库 main，Mihon 从 raw 地址拉取。

**商店地址（填进 Mihon）：**

```
https://raw.githubusercontent.com/1468323775/mihon-store/main/index.json
```

## 商店是什么

一个能被手机公网访问的 HTTPS 地址，返回一个 JSON（`index.json`），里面声明：

- 商店名字/徽标
- `signingKey`：本商店所有扩展的签名证书 SHA-256（小写十六进制）
- 扩展列表：包名、`versionCode`、`versionName`、扩展库版本、内容警告、下载地址、图标地址

Mihon 里「设置 → 浏览 → 插件商店 → 添加插件商店」填这个 URL 就算授权：**该签名Key签出来的扩展一律自动信任**，不用再手点「信任」，而且以后有新版会提示更新。
字段结构对齐 Mihon 源码 `data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt`（2026-09-22 逐字段核对过）。

## 产物构成（CI 生成后提交到本仓库根目录）

```
index.json                                          商店索引（新式 JSON 格式）
apk/tachiyomi-zh.dogemanga-v<版本>.apk              扩展包（release 签名，只保留当前版本）
icon/eu.kanade.tachiyomi.extension.zh.dogemanga.png
```

## 本仓库签名身份（别丢）

- 证书 SHA-256：`e93c713252287646464de377f1e6b65803724e8d0991aa19c12fe45c72e02697`
- keystore：本地 `/home/li/.hermes/keys/mihon-store/signingkey.jks`（PKCS12，别名 `mihonstore`，密码在同目录 `password.txt`）
- GitHub Secrets：`SIGNING_KEY`(base64) / `ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`
- **keystore 丢了 = 这条线断**（已装的扩展无法再更新，只能换包名重来）。备份它。

## 改代码后怎么更新

1. 改 `src/zh/<name>/…`，并把 `build.gradle.kts` 的 `versionCode` +1（实际值 = `libVersion*100000 + versionCode`，如 libVersion 1.6 + versionCode 2 → `106002` / `1.6.2`），否则 Mihon 认不出更新
2. `git push` 到 main → workflow 自动跑：编签名包 → 校指纹 → 校 R8 产物 → 生成商店文件 → 提交发布 → **匿名复验线上可拉**
3. 手动重跑：`gh workflow run build.yml --repo 1468323775/mihon-store`

发布开关是仓库变量 `STORE_PUBLISH=true`，设成别的值就只出产物不发布。

## 加新站

复制 `src/zh/dogemanga` 整个模块目录改名，改 `build.gradle.kts` 里的 `name` / `source { name, lang, baseUrl }`。
workflow 里 `MODULE_DIR` / `GRADLE_PATH` 现在是单模块硬编码，加第二个站要改成矩阵或再加一个 job。

## 验证清单

- [x] `apksigner verify --print-certs` 的 SHA-256 == index.json 的 `signingKey`（CI 硬闸门）
- [x] R8 压缩没吃掉关键串（源名/baseUrl/接口/选择器/状态词 9 项，CI 硬闸门）
- [x] `index.json` 匿名 HTTPS 可拉，`apkUrl` / `iconUrl` 匿名下载 200（CI 硬闸门 + 人工复核）
- [x] `contentWarning` 是枚举名（SAFE/MIXED/NSFW），`versionCode` 是数字
- [ ] **手机实测**：Mihon 加商店 → 装扩展 → 搜「海賊王」→ 读到图（图片靠 KeiSource 基类自动带的 Referer 过防盗链）
- [ ] 手机实测：改了 `versionCode` 后扩展页出现新版提示，点更新能装上

## 踩过的坑

- `.gitignore` 里一句 `*.apk` 会让 `git add apk` **静默跳过**扩展包 —— 索引指向 404、CI 却全绿。
  已改成 `*.apk` + `!apk/*.apk`，并在发布任务末尾加了「匿名拉线上文件 + 索引与仓库文件对齐」的复验闸门。
- 加商店前 Mihon 里要先开 **显示 NSFW 扩展**（本扩展标了 `MIXED`），否则列表里看不到。
- release 构建会被 R8 混淆类名（`La;`/`Lm;`），但 manifest 指向 `keiyoushi.source.Generated`，属正常；
  判断包内容是否完好只能查字符串常量，不能按类名找。
