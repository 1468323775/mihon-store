# 发布 Mihon 插件商店 —— 一键开关

当前状态：**构建 + 商店索引已跑通并验证，只差「发布」这一步（等选托管位置）**。

## 商店是什么

一个能被手机公网访问的 HTTPS 地址，返回一个 JSON（`index.json`），里面声明：

- 商店名字/徽标
- `signingKey`：本商店所有扩展的签名证书 SHA-256（小写十六进制）
- 扩展列表：包名、`versionCode`、`versionName`、扩展库版本、内容警告、下载地址、图标地址

Mihon 里「设置 → 浏览 → 插件商店 → 添加插件商店」填这个 URL 就算授权：**该签名Key签出来的扩展一律自动信任**，不用再手点「信任」，而且以后有新版会提示更新。

## 产物构成（由 CI 生成，`store-bundle` artifact）

```
index.json                                  商店索引（新式 JSON 格式）
apk/tachiyomi-zh.dogemanga-v1.6.1.apk       扩展包（release 签名）
icon/eu.kanade.tachiyomi.extension.zh.dogemanga.png
```

## 本仓库签名身份（别丢）

- 证书 SHA-256：`e93c713252287646464de377f1e6b65803724e8d0991aa19c12fe45c72e02697`
- keystore：本地 `/home/li/.hermes/keys/mihon-store/signingkey.jks`（PKCS12，别名 `mihonstore`，密码在同目录 `password.txt`）
- GitHub Secrets：`SIGNING_KEY`(base64) / `ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`
- **keystore 丢了 = 这条线断**（用户已装的扩展无法再更新，只能换包名重来）。备份它。

## 放开发布（两种托管方式，选一个）

### A. 本仓库直接公开（最省事）

```bash
# 1) 改名成工作流里已经写好的地址
gh repo rename mihon-store --repo 1468323775/dogemanga-mihon-ext --yes
# 2) 设为公开（公开仓库 Actions 分钟数还免费）
gh repo edit 1468323775/mihon-store --visibility public --accept-visibility-change-consequences
# 3) 打开发布开关
gh variable set STORE_PUBLISH --repo 1468323775/mihon-store --body true
# 4) 重跑构建，publish job 会把 index.json/apk/icon 提交进 main
gh workflow run build.yml --repo 1468323775/mihon-store
```

商店地址：`https://raw.githubusercontent.com/1468323775/mihon-store/main/index.json`

### B. 源码留私有 + 成品进另一个公开仓库

1. 建公开仓库 `1468323775/mihon-store`（空仓库即可）
2. 生成部署密钥并把公钥加到公开仓库（Settings → Deploy keys，勾选 write）：
   ```bash
   ssh-keygen -t ed25519 -C mihon-store-publish -f ~/.hermes/keys/mihon-store/publish_key -N ''
   gh repo deploy-key add ~/.hermes/keys/mihon-store/publish_key.pub --repo 1468323775/mihon-store --allow-write
   gh secret set DEPLOY_KEY --repo 1468323775/dogemanga-mihon-ext < ~/.hermes/keys/mihon-store/publish_key
   ```
3. 把 publish job 的 `Checkout` 换成：
   ```yaml
   with:
     repository: 1468323775/mihon-store
     ssh-key: ${{ secrets.DEPLOY_KEY }}
     persist-credentials: true
   ```
   并去掉 `permissions: contents: write`（用部署密钥推）
4. 同样 `gh variable set STORE_PUBLISH ... --body true` 后重跑

## 用户侧操作（一次性）

1. 先卸掉之前 sideload 的 debug 版（签名不同，不卸装不上）
2. Mihon → 设置 → 浏览 → 插件商店 → 添加插件商店 → 填商店索引 URL
3. 浏览 → 扩展 → 找到「Doge Manga」→ 安装 → 不用点信任
4. 浏览 → 图源 → 出现「漫画狗」

## 改代码后怎么更新

直接 push 本仓库；workflow 重新编 release（签名不变）→ publish job 更新 `index.json` 与 `apk/`。
`versionCode` 来自 `src/zh/dogemanga/build.gradle.kts` 的 `versionCode`（实际值 = `libVersion*100000 + versionCode`），
要发新版就把它 +1，否则 Mihon 认不出更新。

## 验证清单（CI 已内置，人工复核也照这个来）

- [ ] `apksigner verify --print-certs` 的 SHA-256 == index.json 的 `signingKey`
- [ ] `index.json` 能通过 HTTPS 拉到，`apkUrl` / `iconUrl` HEAD 200
- [ ] `contentWarning` 是枚举名（SAFE/MIXED/NSFW），`versionCode` 是数字
- [ ] 装完在 Mihon 里能搜到「海賊王」并读到图（图片靠 KeiSource 基类自动带的 Referer 过防盗链）
- [ ] 提交更新后 Mihon 扩展页出现新版提示，点更新能装上
