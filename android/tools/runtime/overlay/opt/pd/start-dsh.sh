#!/bin/bash
# 在 PRoot Debian 内启动 dsh web（由 App 的前台服务调用）。只监听 127.0.0.1。
export HOME=/root TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8
# Android 会把宿主 PATH（/system/bin…）带进来，必须强制成 Debian 的 PATH
export PATH=/opt/pd/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp UV_USE_IO_URING=0
# 用户数据（会话/设置/凭据）放在持久目录 /root/projects（绑定到 App 的 files/home），不放 rootfs，
# 这样运行时升级重新解包也不会丢数据
export DSH_HOME=/root/projects/.dsh DSH_TELEMETRY_DISABLED=1 DSH_PERMISSION_MODE=danger-full-access
# Built-in skills are shipped in the mounted /opt/pd tree. The standard dsh preset already
# provides skill-filesystem; this variable only makes the bundled directory discoverable.
export DSH_BUNDLED_SKILL_DIR=/opt/pd/skills
mkdir -p "$DSH_HOME" /BOT/default /tmp && cd /BOT/default
exec /usr/local/bin/node --preserve-symlinks --preserve-symlinks-main --import file:///opt/pd/link-shim.mjs \
  /opt/pd/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --patch /opt/pd/privacy.patch.yml --patch /opt/pd/history.patch.yml --patch /opt/pd/standard-questions.patch.yml --no-open --host 127.0.0.1 --port "${PD_DSH_PORT:-3090}"
