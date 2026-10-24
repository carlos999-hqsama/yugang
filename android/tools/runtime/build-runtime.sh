#!/usr/bin/env bash
# 一键准备一体化 APK 的内置运行时：下载（固定版本 + 校验）→ 打包成 android/runtime-assets/runtime/runtime.tar.xz
# 之后：cd android && ./gradlew assembleRelease
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
"$HERE/fetch.sh"
python3 "$HERE/pack.py"

python3 "$HERE/../../../scripts/check-runtime-distribution.py" "$HERE/../../runtime-assets/runtime/runtime.tar.xz"
