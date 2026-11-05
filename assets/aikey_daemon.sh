#!/system/bin/sh
# AI 键守护进程（"守护进程模式"）：直接读 gpio-keys，识别 单击 / 双击 / 长按 并派发动作。
#
# 移植自 KernelSU 模块 aikey_plus v1.1.1（作者 NekoWannn）的 bin/aikey_daemon.sh：
# 数据目录改到应用私有目录，按键名匹配额外兼容数字键码（不同 getevent 版本命名不一致）。

SELF=$0
REAL=$(readlink -f "$SELF" 2>/dev/null)
[ -n "$REAL" ] && SELF=$REAL
AIKEY_DIR=$(cd "${SELF%/*}" 2>/dev/null && pwd)
AIKEY_DIR=${AIKEY_DIR:-${SELF%/*}}
export AIKEY_DIR

CONF=$AIKEY_DIR/config.conf

. "$AIKEY_DIR/aikey_lib.sh"

mkdir -p "$AIKEY_STATE" 2>/dev/null
echo $$ > "$AIKEY_DIR/daemon.pid"

# 单实例锁：避免重复拉起时两个守护进程同时监听（会双触发）
LOCK=$AIKEY_DIR/daemon.lock
if ! mkdir "$LOCK" 2>/dev/null; then
    _old=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$_old" ] && kill -0 "$_old" 2>/dev/null; then
        ai_log "已有守护进程 $_old 在运行，本次退出"
        exit 0
    fi
    rm -rf "$LOCK" 2>/dev/null
    mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid"

find_event() {
    for _d in /sys/class/input/event*; do
        [ -r "$_d/device/name" ] || continue
        if [ "$(cat "$_d/device/name" 2>/dev/null)" = "gpio-keys" ]; then
            echo "/dev/input/${_d##*/}"
            return 0
        fi
    done
    return 1
}

ai_log "守护进程启动 pid=$$ dir=$AIKEY_DIR"

while :; do
    EV=${EV:-$(conf EV)}
    [ -n "$EV" ] || EV=$(find_event)
    if [ -z "$EV" ]; then
        ai_log "未找到 gpio-keys，3 秒后重试"
        sleep 3
        continue
    fi
    ai_log "开始监听 $EV"

    # getevent 单设备时输出：[ 1234.567890] EV_KEY KEY_XXX DOWN
    # 输出给下游的协议：D <按下时刻 ms> / U <持续时长 ms>
    getevent -lt "$EV" 2>>"$AIKEY_LOG" | awk '
        $3 == "EV_KEY" && ($5 == "DOWN" || $5 == "UP") &&
        ($4 ~ /RECORD_STO|KEY_AI|AI_KEY/ || $4 == "689" || $4 == "690" || $4 == "0x2b1") {
            ts = $2 + 0
            if ($5 == "DOWN") {
                if (down == 0) {
                    down = ts
                    printf "D %d\n", int(ts * 1000)
                    fflush()
                }
            } else if (down > 0) {
                printf "U %d\n", int((ts - down) * 1000)
                fflush()
                down = 0
            }
        }' | {
            while read -r tag val; do
                # 只处理抬起事件；按下事件用来给双击计时
                [ "$tag" = "U" ] || continue
                # App 正在借系统执行原生动作时，注入的这次按下不要重复处理
                if [ "$(settings get system aikey_native_busy 2>/dev/null)" = "1" ]; then
                    continue
                fi
                dur=$val
                case "$dur" in ''|*[!0-9]*) dur=0 ;; esac
                if [ "$dur" -lt 20 ]; then
                    continue
                fi

                [ -f "$CONF" ] && . "$CONF" 2>/dev/null
                long_ms=${LONG_MS:-600}
                double_ms=${DOUBLE_MS:-350}
                case "$long_ms" in ''|*[!0-9]*) long_ms=600 ;; esac
                case "$double_ms" in ''|*[!0-9]*) double_ms=350 ;; esac

                if [ "$dur" -ge "$long_ms" ]; then
                    run_action "${LONG:-none}"
                    continue
                fi

                # 没配双击就不等窗口，单击立即响应
                if [ -z "$DOUBLE" ] || [ "$DOUBLE" = "none" ]; then
                    run_action "${SINGLE:-none}"
                    continue
                fi

                # 等下一次"按下"。窗口额外放宽 80ms，抵消脚本/管道本身几十毫秒的延迟。
                win=$(awk -v ms="$double_ms" 'BEGIN { printf "%.3f", (ms + 80) / 1000 }')
                if read -r -t "$win" tag2 val2 && [ "$tag2" = "D" ]; then
                    if read -r tag3 val3; then
                        case "$val3" in ''|*[!0-9]*) val3=0 ;; esac
                        if [ "$val3" -lt "$long_ms" ]; then
                            run_action "${DOUBLE:-none}"
                        else
                            run_action "${LONG:-none}"
                        fi
                    fi
                    continue
                fi

                run_action "${SINGLE:-none}"
            done
        }

    ai_log "getevent 退出，1 秒后重连"
    sleep 1
done
