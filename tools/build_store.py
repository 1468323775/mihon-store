#!/usr/bin/env python3
"""从多个扩展模块的构建产物生成 Mihon 商店文件（index.json + apk/ + icon/）。

产物目录结构（CI 里 download-artifact 下来的样子，每个模块一个子目录）：

    artifacts/
      ext-src-zh-dogemanga/
        keiyoushi-source-info.json
        tachiyomi-zh.dogemanga-v1.6.2.apk
        ic_launcher.png
      ext-src-zh-copymanga/
        ...

字段结构对齐 Mihon 源码 data/.../extension/model/NetworkExtensionStore.kt。

用法:
    python tools/build_store.py --artifacts <目录> --out <输出目录> --base-url <商店URL> \\
        --name <商店名> --badge <徽标> --website <站点> --signing-key <证书指纹>
"""
import argparse, hashlib, json, shutil, sys
from pathlib import Path

CONTENT_WARNING_NAMES = {0: "UNSPECIFIED", 1: "SAFE", 2: "MIXED", 3: "NSFW"}


def pick(info, *keys, default=None):
    for k in keys:
        if k in info and info[k] is not None:
            return info[k]
    return default


def normalize_content_warning(raw):
    if isinstance(raw, bool):
        return "SAFE"
    if isinstance(raw, (int, float)):
        return CONTENT_WARNING_NAMES.get(int(raw), "SAFE")
    name = str(raw).upper().removeprefix("CONTENT_WARNING_").strip()
    if name.isdigit():
        return CONTENT_WARNING_NAMES.get(int(name), "SAFE")
    return name if name in set(CONTENT_WARNING_NAMES.values()) else "SAFE"


def find_file(base: Path, pattern: str, prefer: str | None = None) -> Path | None:
    """在产物目录里递归找文件（CI 的 artifact 会保留 build/outputs/... 这种层级）"""
    hits = sorted(p for p in base.rglob(pattern) if p.is_file())
    if prefer:
        for hit in hits:
            if hit.name == prefer:
                return hit
    return hits[0] if hits else None


def build_extension(info_path: Path, artifact_root: Path, out: Path, base: str) -> dict:
    info = json.loads(info_path.read_text(encoding="utf-8"))
    pkg = pick(info, "packageName", "package_name")
    if not pkg:
        sys.exit(f"{info_path} 里没有 packageName")

    # CI 的 artifact 会保留 build/outputs/apk/release、res/mipmap-xhdpi 这种层级，所以要递归找
    module = str(pick(info, "module", default="")).split(".")[-1]
    apks = sorted(p for p in artifact_root.rglob("*.apk") if p.is_file())
    # 只认 release 包（万一同目录混进 debug 包，别挑错）
    pool = [p for p in apks if "release" in p.as_posix() and "debug" not in p.name.lower()]
    pool = pool or [p for p in apks if "debug" not in p.name.lower()] or apks
    preferred = [p for p in pool if module and module in p.name]
    apk = (preferred or pool)[0] if (preferred or pool) else None
    icon = find_file(artifact_root, "*.png", prefer="ic_launcher.png")
    if apk is None:
        sys.exit(f"{artifact_root} 下（递归）找不到 APK：{[str(p) for p in artifact_root.rglob('*')]}")
    if icon is None:
        sys.exit(f"{artifact_root} 下（递归）找不到图标：{[str(p) for p in artifact_root.rglob('*')]}")

    (out / "apk").mkdir(parents=True, exist_ok=True)
    (out / "icon").mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, out / "apk" / apk.name)
    icon_name = f"{pkg}.png"
    shutil.copy2(icon, out / "icon" / icon_name)
    apk_sha256 = hashlib.sha256((out / "apk" / apk.name).read_bytes()).hexdigest()

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

    extension = {
        "name": pick(info, "name", default=pkg),
        "packageName": pkg,
        "resources": {
            "apkUrl": f"{base}/apk/{apk.name}",
            "iconUrl": f"{base}/icon/{icon_name}",
        },
        "extensionLib": str(pick(info, "extensionLib", default="1.6")),
        "versionCode": int(pick(info, "versionCode", default=1)),
        "versionName": str(pick(info, "versionName", default="1.0")),
        "contentWarning": normalize_content_warning(pick(info, "contentWarning", default="SAFE")),
        "sources": sources,
    }
    print(f"[store] {extension['name']:12} {pkg:48} {extension['versionName']:7} "
          f"({extension['versionCode']}) 源={[s['name'] for s in sources]} "
          f"apk={apk.name} sha256={apk_sha256[:16]}…")
    return extension


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--artifacts", required=True, help="各模块产物目录（含子目录）")
    p.add_argument("--out", required=True)
    p.add_argument("--base-url", required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--badge", required=True)
    p.add_argument("--website", required=True)
    p.add_argument("--signing-key", required=True)
    args = p.parse_args()

    artifacts = Path(args.artifacts)
    infos = sorted(artifacts.glob("**/keiyoushi-source-info.json"))
    if not infos:
        sys.exit(f"{artifacts} 下没找到任何 keiyoushi-source-info.json")

    out = Path(args.out)
    base = args.base_url.rstrip("/")
    extensions = []
    for info in infos:
        # artifact 根目录 = artifacts/<artifact 名>/（source-info 可能埋在里面几层）
        rel = info.relative_to(artifacts)
        artifact_root = artifacts / rel.parts[0] if len(rel.parts) > 1 else info.parent
        extensions.append(build_extension(info, artifact_root, out, base))
    extensions.sort(key=lambda e: e["packageName"])

    index = {
        "name": args.name,
        "badgeLabel": args.badge,
        "signingKey": args.signing_key,
        "contact": {"website": args.website, "discord": None},
        "extensionList": {"extensions": extensions},
        "extensionListUrl": None,
    }
    (out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"\n[store] 共 {len(extensions)} 个扩展：")
    for e in extensions:
        print(f"    {e['name']:14} {e['versionName']:8} {e['contentWarning']:6} {e['resources']['apkUrl']}")


if __name__ == "__main__":
    main()
