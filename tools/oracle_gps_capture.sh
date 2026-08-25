#!/system/bin/sh

DURATION="${1:-1200}"
INTERVAL="${2:-5}"
STAMP="$(date +%Y%m%d_%H%M%S 2>/dev/null)"
if [ -z "$STAMP" ]; then
  STAMP="unknown"
fi

OUT_DIR="/sdcard/oracle-gps"
mkdir -p "$OUT_DIR" 2>/dev/null

SUMMARY="$OUT_DIR/gps_capture_${STAMP}_summary.txt"
RAW="$OUT_DIR/gps_capture_${STAMP}_logcat.txt"
PIDFILE="$OUT_DIR/gps_capture_${STAMP}.pid"

echo "$$" > "$PIDFILE" 2>/dev/null

echo "Oracle Compass GPS capture" > "$SUMMARY"
echo "start=$(date 2>/dev/null)" >> "$SUMMARY"
echo "duration=${DURATION}s interval=${INTERVAL}s" >> "$SUMMARY"
echo "summary=$SUMMARY" >> "$SUMMARY"
echo "raw=$RAW" >> "$SUMMARY"
echo "" >> "$SUMMARY"

logcat -c 2>/dev/null
logcat -v time \
  GpsLocationProvider:V \
  gps_mtk:D \
  mnl_linux:D \
  MNLD:D \
  MtkAgpsNative:D \
  agps:D \
  YGPS/Activity:V \
  '*:S' > "$RAW" 2>&1 &
LOGCAT_PID="$!"

START="$(date +%s 2>/dev/null)"
if [ -z "$START" ]; then
  START=0
fi

elapsed=0
while [ "$elapsed" -lt "$DURATION" ]; do
  NOW_TEXT="$(date 2>/dev/null)"
  NOW_SEC="$(date +%s 2>/dev/null)"
  if [ -n "$NOW_SEC" ] && [ "$START" -gt 0 ]; then
    elapsed=$((NOW_SEC - START))
  else
    elapsed=$((elapsed + INTERVAL))
  fi

  echo "===== sample elapsed=${elapsed}s time=${NOW_TEXT} =====" >> "$SUMMARY"

  echo "[settings]" >> "$SUMMARY"
  settings get secure location_mode >> "$SUMMARY" 2>&1
  settings get secure location_providers_allowed >> "$SUMMARY" 2>&1
  settings get global assisted_gps_enabled >> "$SUMMARY" 2>&1

  echo "[gps driver]" >> "$SUMMARY"
  for f in /sys/class/gpsdrv/gps/pwrctl /sys/class/gpsdrv/gps/state /sys/class/gpsdrv/gps/pwrsave /sys/class/gpsdrv/gps/suspend /sys/class/gpsdrv/gps/status; do
    echo "$f=$(cat "$f" 2>/dev/null)" >> "$SUMMARY"
  done

  echo "[location]" >> "$SUMMARY"
  dumpsys location 2>/dev/null | grep -E "GNSS SV count|ephemerisMask|almanacMask|gps Location|net Location|UpdateRecord\\[gps|mFixInterval|AVAILABLE|UNAVAILABLE" >> "$SUMMARY" 2>&1

  echo "[assistance files]" >> "$SUMMARY"
  ls -l /data/misc/EPO.DAT /data/misc/BEE.BIN /data/misc/ARC.BIN /data/misc/LOCATION.DAT /data/misc/mtkgps.dat /data/misc/mnl_nlp.dat 2>&1 >> "$SUMMARY"
  echo "" >> "$SUMMARY"

  sleep "$INTERVAL"
done

kill "$LOGCAT_PID" 2>/dev/null
echo "end=$(date 2>/dev/null)" >> "$SUMMARY"
echo "done" >> "$SUMMARY"
