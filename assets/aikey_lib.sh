#!/system/bin/sh
# 动作库：dispatch.sh / aikey_daemon.sh 共用，运行身份 root。
#
# 移植自 KernelSU 模块 aikey_plus v1.1.1（作者 NekoWannn）的 bin/aikey_lib.sh。
# 改动：数据目录从 /data/adb/aikey 改为应用私有目录（AIKEY_DIR），
#       去掉 WebUI 用的 state.json，其余动作实现保持原样。

AIKEY_DIR=${AIKEY_DIR:-$(cd "$(dirname "$0")" 2>/dev/null && pwd)}
AIKEY_DATA=$AIKEY_DIR
AIKEY_STATE=$AIKEY_DATA/state
AIKEY_LOG=$AIKEY_DATA/aikey.log
RECORDER_PKG=com.miui.screenrecorder
RECORDER_FN=com.miui.screenrecorder.appfunction.ScreenRecorderAppFunction

mkdir -p "$AIKEY_STATE" 2>/dev/null

ai_log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >> "$AIKEY_LOG" 2>/dev/null
    # 防止日志无限增长
    if [ -f "$AIKEY_LOG" ] && [ "$(wc -c < "$AIKEY_LOG" 2>/dev/null || echo 0)" -gt 131072 ]; then
        tail -n 400 "$AIKEY_LOG" > "$AIKEY_LOG.tmp" 2>/dev/null
        mv -f "$AIKEY_LOG.tmp" "$AIKEY_LOG" 2>/dev/null
    fi
}

# 读一个配置项（去掉引号和反斜杠，避免写 JSON 时出错）
conf_val() {
    sed -n "s/^$1=//p" "$AIKEY_DATA/config.conf" 2>/dev/null | tail -n 1 | tr -d '"\\'
}

# 按输入设备名找 event 节点，例如 find_input_dev pmic_pwrkey
find_input_dev() {
    for _d in /sys/class/input/event*; do
        [ -r "$_d/device/name" ] || continue
        if [ "$(cat "$_d/device/name" 2>/dev/null)" = "$1" ]; then
            echo "/dev/input/${_d##*/}"
            return 0
        fi
    done
    return 1
}

screen_awake() {
    dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'
}

# ---------- 手电筒 ----------
torch_write() {
    # $1 = 1 开，0 关
    _lvl=${TORCH_LEVEL:-150}
    case "$_lvl" in ''|*[!0-9]*) _lvl=150 ;; esac
    [ "$_lvl" -lt 1 ] && _lvl=150
    [ "$_lvl" -gt 255 ] && _lvl=255
    if [ "$1" = "1" ]; then
        _val=$_lvl
    else
        _val=0
    fi
    _hit=0
    # 小米的双色温闪光灯：白 + 黄各一个节点
    for _led in /sys/class/leds/white:flash-1/brightness /sys/class/leds/yellow:flash-0/brightness; do
        [ -w "$_led" ] || continue
        echo "$_val" > "$_led" 2>/dev/null && _hit=1
    done
    # 其它机型/改名后的节点兜底
    if [ "$_hit" = "0" ]; then
        for _led in /sys/class/leds/*flash*/brightness; do
            [ -w "$_led" ] || continue
            echo "$_val" > "$_led" 2>/dev/null && _hit=1
        done
    fi
    [ "$_hit" = "1" ] || ai_log "写闪光灯节点失败（可能被 SELinux 拦了）"
}

torch_toggle() {
    mkdir -p "$AIKEY_STATE" 2>/dev/null
    _cur=0
    [ -f "$AIKEY_STATE/torch" ] && _cur=$(cat "$AIKEY_STATE/torch" 2>/dev/null)
    if [ "$_cur" = "1" ]; then
        torch_write 0
        echo 0 > "$AIKEY_STATE/torch"
    else
        torch_write 1
        echo 1 > "$AIKEY_STATE/torch"
    fi
}

# ---------- 截图（走系统原生的 音量下+电源 组合键） ----------
ai_screenshot() {
    if ! screen_awake; then
        ai_log "屏幕未点亮，跳过截图"
        return 1
    fi
    _vold=$(find_input_dev pmic_resin)
    _pwr=$(find_input_dev pmic_pwrkey)
    if [ -n "$_vold" ] && [ -n "$_pwr" ]; then
        # 先按住音量下，再点电源，最后依次松开 —— 与真人按组合键一致
        sendevent "$_vold" 1 114 1
        sendevent "$_vold" 0 0 0
        sleep 0.04
        sendevent "$_pwr" 1 116 1
        sendevent "$_pwr" 0 0 0
        sleep 0.12
        sendevent "$_pwr" 1 116 0
        sendevent "$_pwr" 0 0 0
        sleep 0.04
        sendevent "$_vold" 1 114 0
        sendevent "$_vold" 0 0 0
        ai_log "已触发系统截图"
        return 0
    fi
    # 找不到按键设备时退回直接抓屏
    _dir=/sdcard/DCIM/Screenshots
    _name="Screenshot_AI_$(date '+%Y%m%d_%H%M%S').png"
    mkdir -p "$_dir" 2>/dev/null
    screencap -p "$_dir/$_name" 2>/dev/null || return 1
    am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file://$_dir/$_name" >/dev/null 2>&1
    ai_log "已截图（抓屏兜底）: $_dir/$_name"
}

# ---------- 录屏（MIUI 系统录屏的 AppFunction 接口） ----------
recorder_call() {
    # $1 = startRecording | stopRecording | getRecordingStatus
    cmd app_function execute-app-function --package "$RECORDER_PKG" \
        --function "$RECORDER_FN#$1" --parameters '{}' 2>/dev/null
}

recorder_status() {
    _out=$(recorder_call getRecordingStatus)
    case "$_out" in
        *'"isRecording"'*) ;;
        *) echo unknown; return 0 ;;
    esac
    if echo "$_out" | tr -d ' \n' | grep -q '"isRecording":\[true\]'; then
        echo 1
    else
        echo 0
    fi
}

record_start() {
    _out=$(recorder_call startRecording)
    case "$_out" in
        *'"success":'*'true'*) ai_log "开始录屏" ;;
        *) ai_log "录屏启动失败（系统录屏没有暴露 AppFunction 接口？）" ;;
    esac
}

record_stop() {
    _out=$(recorder_call stopRecording)
    case "$_out" in
        *'"success":'*'true'*) ai_log "停止录屏（已保存到相册）" ;;
        *) ai_log "录屏停止失败" ;;
    esac
}

record_toggle() {
    if [ "$(recorder_status)" = "1" ]; then
        record_stop
    else
        record_start
    fi
}

# ---------- 原生动作：让系统自己去执行 ----------
# 先找 gpio-keys 的 event 节点（AI 键在内核里的键码是 689）
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

# 把 click_mi_ai_key 临时设成系统认的值，再注入一次 AI 键，
# 系统就按原生逻辑执行（付款码 / 防窥 / 静音 / 小爱记忆 / 正背屏选择…）。
run_native() {
    _val=$1
    _ev=$(find_event)
    if [ -z "$_ev" ]; then
        ai_log "原生动作失败：找不到 gpio-keys（$_val）"
        return 1
    fi
    ai_log "原生动作: $_val"
    # 让 LSPosed 钩子知道这次注入是给系统用的，别吞掉
    settings put system aikey_native_busy 1 2>/dev/null
    settings put system click_mi_ai_key "$_val" 2>/dev/null
    sleep 0.15
    sendevent "$_ev" 1 689 1
    sendevent "$_ev" 0 0 0
    sleep 0.07
    sendevent "$_ev" 1 689 0
    sendevent "$_ev" 0 0 0
    sleep 0.45
    settings put system click_mi_ai_key none 2>/dev/null
    settings put system aikey_native_busy 0 2>/dev/null
    return 0
}

# ---------- 背屏录屏 ----------
back_rec_toggle() {
    _pidf="$AIKEY_STATE/backrec.pid"
    if [ -f "$_pidf" ]; then
        _p=$(cat "$_pidf" 2>/dev/null)
        if [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null; then
            kill -INT "$_p" 2>/dev/null
            sleep 0.6
            kill -9 "$_p" 2>/dev/null
            rm -f "$_pidf"
            ai_log "背屏录屏已停止"
            return 0
        fi
        rm -f "$_pidf"
    fi
    _dir=/sdcard/DCIM/Screenshots
    mkdir -p "$_dir" 2>/dev/null
    _out="$_dir/BackScreen_$(date '+%Y%m%d_%H%M%S').mp4"
    screenrecord --display-id "${BACK_DISPLAY:-1}" --time-limit 180 "$_out" \
        >/dev/null 2>&1 &
    echo $! > "$_pidf"
    ai_log "背屏录屏开始: $_out"
}

# ---------- 其它 ----------
launch_app() {
    _pkg=$1
    [ -n "$_pkg" ] || return 0
    monkey -p "$_pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && return 0
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        -p "$_pkg" >/dev/null 2>&1
}

run_action() {
    _act=$1
    case "$_act" in
        ''|none)
            ;;
        flashlight)
            torch_toggle
            ;;
        torch_on)
            torch_write 1
            echo 1 > "$AIKEY_STATE/torch" 2>/dev/null
            ;;
        torch_off)
            torch_write 0
            echo 0 > "$AIKEY_STATE/torch" 2>/dev/null
            ;;
        screenshot)
            ai_screenshot
            ;;
        record|record_toggle)
            record_toggle
            ;;
        record_start)
            record_start
            ;;
        record_stop)
            record_stop
            ;;
        game_mode)
            am start -n com.miui.securitycenter/com.miui.gamebooster.ui.WelcomActivity \
                >/dev/null 2>&1
            ;;
        camera)
            am start -a android.media.action.STILL_IMAGE_CAMERA >/dev/null 2>&1
            ;;
        home)
            am start -a android.intent.action.MAIN -c android.intent.category.HOME \
                >/dev/null 2>&1
            ;;
        app:*)
            launch_app "${_act#app:}"
            ;;
        backhome)
            # 背屏回桌面（等价于把背屏上的应用关掉）
            am start --display "${BACK_DISPLAY:-1}" -n com.xiaomi.subscreencenter/.SubScreenLauncher \
                >/dev/null 2>&1
            ai_log "背屏回桌面"
            ;;
        backrec)
            back_rec_toggle
            ;;
        appback:*)
            # 背屏显示应用：值是 "包名/Activity"
            _spec=${_act#appback:}
            _disp=${BACK_DISPLAY:-1}
            am start --display "$_disp" -n "$_spec" >/dev/null 2>&1
            ai_log "背屏启动: $_spec (display $_disp)"
            ;;
        shell:*)
            sh -c "${_act#shell:}" >/dev/null 2>&1 &
            ;;
        native:*)
            run_native "${_act#native:}"
            ;;
        *)
            # 命令表：由 App 写的 commands.conf，一行一个 "id=命令"
            _cmd=$(sed -n "s|^$_act=||p" "$AIKEY_DATA/commands.conf" 2>/dev/null | tail -n 1)
            if [ -n "$_cmd" ]; then
                sh -c "$_cmd" >/dev/null 2>&1 &
                ai_log "执行命令: $_cmd"
            else
                ai_log "未知动作: $_act"
            fi
            ;;
    esac
    ai_log "执行动作: $_act"
}
