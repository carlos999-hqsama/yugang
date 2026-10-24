#!/usr/bin/env bash
# Pinned public downloads. Every cached archive is verified on every invocation.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export RT="${RT:-$HERE/../../../.rtbuild}"
python3 "$HERE/fetch-runtime.py"
mkdir -p "$RT/dl/dsh"
cp "$HERE/package.json" "$HERE/package-lock.json" "$RT/dl/dsh/"
cd "$RT/dl/dsh"
npm ci --ignore-scripts --include=optional --no-audit --no-fund --os=linux --cpu=arm64 --libc=glibc
python3 "$HERE/runtime_inputs.py" "$RT/dl/dsh"
