#!/system/bin/sh
# Read-only Android 5.1 ROM audit. It intentionally does not disable packages.
OUT="${1:-/sdcard/rom-audit}"
mkdir -p "$OUT" || exit 1

getprop > "$OUT/getprop.txt"
pm list packages -s > "$OUT/system-packages.txt"
pm list packages -3 > "$OUT/third-party-packages.txt"
dumpsys activity services > "$OUT/services.txt"
dumpsys sensorservice > "$OUT/sensors.txt"
dumpsys location > "$OUT/location.txt"
cat /proc/loadavg > "$OUT/loadavg.txt"
cat /proc/meminfo > "$OUT/meminfo.txt"

for p in com.adups.fota com.adups.fota.sysoper; do
    {
        echo "=== $p ==="
        pm path "$p" 2>&1
        dumpsys package "$p" 2>&1 | grep -E "Package\[|enabled=|stopped="
    } >> "$OUT/vendor-ota.txt"
done

echo "read-only audit written to $OUT"
