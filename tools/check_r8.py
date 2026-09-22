#!/usr/bin/env python3
"""校验 release(R8) 包里扩展的关键串没被压缩干掉。

用法:
    python tools/check_r8.py <apk> <keiyoushi-source-info.json> <模块目录，如 src/zh/copymanga>

标记表在 tools/r8_markers.json；脚本还会自动加上 source-info 里的「源名」和「baseUrl」，
所以新模块即使忘了补标记表也有兜底。原因：R8 会把我们自己的类名混淆成 La;/Lm;，
按类名找不到不代表出错，只能查字符串常量。
"""
import json
import sys
import zipfile
from pathlib import Path

MARKERS_FILE = Path(__file__).resolve().parent / "r8_markers.json"


def main() -> int:
    apk, info_path, module = sys.argv[1], sys.argv[2], sys.argv[3]
    info = json.loads(Path(info_path).read_text(encoding="utf-8"))
    source = (info.get("sources") or [{}])[0]
    markers = {
        f"源名 {source.get('name', '?')}": source.get("name", ""),
        f"baseUrl {source.get('baseUrl', '?')}": source.get("baseUrl", ""),
    }
    extra = json.loads(MARKERS_FILE.read_text(encoding="utf-8")).get(module, [])
    for item in extra:
        markers[item] = item

    with zipfile.ZipFile(apk) as z:
        blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".dex"))

    bad = []
    for label, needle in markers.items():
        if not needle:
            continue
        ok = needle.encode() in blob
        print(("  ✅ " if ok else "  ❌ ") + label)
        if not ok:
            bad.append(label)

    if bad:
        sys.exit("R8 把关键串干掉了: " + ", ".join(bad))
    print(f"✅ {module} 关键串 {len([v for v in markers.values() if v])} 项全在")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
