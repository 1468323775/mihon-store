#!/usr/bin/env python3
"""从 assembleRelease 产物生成 Mihon 扩展商店文件（index.json + apk/ + icon/）。

Mihon 的「新式商店」格式：单文件 JSON，字段名与 data/extension/model/NetworkExtensionStore.kt 一一对应。
apkUrl / iconUrl 必须是绝对 URL（商店基础地址 + 相对路径）。

用法:
    python tools/build_store.py --info <keiyoushi-source-info.json> --apk <x.apk> \
        --icon <ic_launcher.png> --out <商店目录> --base-url <商店URL> \
        --name <商店名> --badge <徽标> --website <站点> --signing-key <证书指纹>
"""
import argparse, hashlib, json, shutil, sys
from pathlib import Path


def load_info(info_path: Path) -> dict:
    info = json.loads(info_path.read_text(encoding="utf-8"))
    print("[info] 原始 keiyoushi-source-info.json:")
    print(json.dumps(info, ensure_ascii=False, indent=2))
    return info


def pick(info: dict, *keys, default=None):
    for k in keys:
        if k in info and info[k] is not None:
            return info[k]
    return default


def need(info: dict, *keys, what=""):
    value = pick(info, *keys)
    if value is None:
        sys.exit(f"source-info 缺少必需字段 {what or keys[0]}：可用字段 {sorted(info)}")
    return value


def build(args) -> dict:
    out = Path(args.out)
    (out / "apk").mkdir(parents=True, exist_ok=True)
    (out / "icon").mkdir(parents=True, exist_ok=True)

    info = load_info(Path(args.info))
    pkg = pick(info, "packageName", "package_name")
    if not pkg:
        sys.exit("source-info 里没有 packageName，无法生成商店索引")

    apk_src = Path(args.apk)
    apk_dst = out / "apk" / apk_src.name
    shutil.copy2(apk_src, apk_dst)
    apk_sha256 = hashlib.sha256(apk_dst.read_bytes()).hexdigest()

    icon_dst = out / "icon" / f"{pkg}.png"
    shutil.copy2(args.icon, icon_dst)

    sources = []
    for s in pick(info, "sources", default=[]) or []:
        sources.append({
            "id": int(pick(s, "id", default=0) or 0),
            "name": pick(s, "name", default=pkg),
            "language": pick(s, "lang", "language", default=pick(info, "lang", default="zh")),
            "homeUrl": pick(s, "baseUrl", "homeUrl", default=""),
            "mirrorUrls": pick(s, "mirrorUrls", "mirror_urls", default=[]) or [],
        })
    if not sources:
        sources = [{"id": 0, "name": pick(info, "name", default=pkg), "language": "zh",
                    "homeUrl": "", "mirrorUrls": []}]

    content_warning = pick(info, "contentWarning", default="SAFE")
    # 插件里枚举的序列化名就是常量名（SAFE/MIXED/NSFW），protobuf 风格名也接受
    content_warning = str(content_warning).upper().replace("CONTENT_WARNING_", "")

    base = args.base_url.rstrip("/")
    extension = {
        "name": pick(info, "name", default=pkg),
        "packageName": pkg,
        "resources": {
            "apkUrl": f"{base}/apk/{apk_dst.name}",
            "iconUrl": f"{base}/icon/{icon_dst.name}",
        },
        "extensionLib": str(pick(info, "extensionLib", default="1.6")),
        "versionCode": int(need(info, "versionCode", what="versionCode")),
        "versionName": str(need(info, "versionName", what="versionName")),
        "contentWarning": content_warning,
        "sources": sources,
    }

    index = {
        "name": args.name,
        "badgeLabel": args.badge,
        "signingKey": args.signing_key,
        "contact": {"website": args.website, "discord": None},
        "extensionList": {"extensions": [extension]},
        "extensionListUrl": None,
    }
    (out / "index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print("\n[store] index.json:")
    print(json.dumps(index, ensure_ascii=False, indent=2))
    print(f"\n[store] apk  -> {apk_dst.name}  sha256={apk_sha256}")
    print(f"[store] icon -> {icon_dst.name}")
    print(f"[store] versionCode={extension['versionCode']} versionName={extension['versionName']}"
          f" contentWarning={content_warning} extensionLib={extension['extensionLib']}")
    return index


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--info", required=True)
    p.add_argument("--apk", required=True)
    p.add_argument("--icon", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--base-url", required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--badge", required=True)
    p.add_argument("--website", required=True)
    p.add_argument("--signing-key", required=True)
    build(p.parse_args())


if __name__ == "__main__":
    main()
