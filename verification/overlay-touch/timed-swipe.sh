#!/system/bin/sh
# 仅执行已有测试的固定时长滑动；结束时间为input命令返回后的设备boottime，不是屏幕绘制时刻。
[ "$#" -eq 4 ] || exit 64
for coordinate in "$@"; do
    case "$coordinate" in ''|*[!0-9]*) exit 64;; esac
done
input swipe "$1" "$2" "$3" "$4" 450
input_status=$?
[ "$input_status" -eq 0 ] || exit "$input_status"
read -r boot_seconds ignored < /proc/uptime || exit 65
printf 'INPUT_FINISHED_SECONDS=%s\n' "$boot_seconds"
