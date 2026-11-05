#!/system/bin/sh
# 单次动作入口：由 App 的 root shell 调用。
#   sh dispatch.sh flashlight
#   sh dispatch.sh 'app:com.tencent.mm'
AIKEY_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
AIKEY_DIR=${AIKEY_DIR:-$(dirname "$0")}

. "$AIKEY_DIR/aikey_lib.sh"

[ -f "$AIKEY_DIR/config.conf" ] && . "$AIKEY_DIR/config.conf" 2>/dev/null

[ -n "$1" ] || exit 1
run_action "$1"
