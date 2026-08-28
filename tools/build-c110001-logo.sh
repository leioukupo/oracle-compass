#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 MTKLOGO STOCK_LOGO FIRST_FRAME OUTPUT_LOGO" >&2
  exit 2
fi

mtklogo=$1
stock_logo=$2
first_frame=$3
output_logo=$4
repo_root=$(cd "$(dirname "$0")/.." && pwd)
profile="$repo_root/tools/mtklogo-c110001.yaml"

for path in "$mtklogo" "$stock_logo" "$first_frame" "$profile"; do
  test -s "$path" || { echo "missing input: $path" >&2; exit 3; }
done

work=$(mktemp -d)
trap 'find "$work" -type f -exec shred -u {} + 2>/dev/null || true; rmdir "$work" 2>/dev/null || true' EXIT
unpacked="$work/unpacked"
verified="$work/verified"
mkdir -p "$unpacked" "$verified"

"$mtklogo" unpack --config "$profile" --profile c110001 \
  --slots 0,38 --output "$unpacked" "$stock_logo" >/dev/null

for slot_number in 000 038; do
  slot="$unpacked/logo_${slot_number}_rgbabe.png"
  test -s "$slot" || {
    echo "slot $((10#$slot_number)) did not decode as 800x800 rgbabe" >&2
    exit 4
  }
  ffmpeg -hide_banner -loglevel error -y -i "$first_frame" \
    -vf "scale=800:800:flags=lanczos,colorchannelmixer=rr=0:rb=1:gg=1:br=1:bb=0,format=rgba" \
    "$slot"
done

mapfile -t inputs < <(find "$unpacked" -maxdepth 1 -type f \
  \( -name 'logo_*.png' -o -name 'logo_*_raw.z' \) | sort)
test "${#inputs[@]}" -eq 39 || {
  echo "expected 39 logo slots, found ${#inputs[@]}" >&2
  exit 5
}

"$mtklogo" repack --output "$work/repacked.bin" "${inputs[@]}" >/dev/null
stock_size=$(stat -c %s "$stock_logo")
repacked_size=$(stat -c %s "$work/repacked.bin")
test "$repacked_size" -le "$stock_size" || {
  echo "repacked logo exceeds partition image: $repacked_size > $stock_size" >&2
  exit 6
}
cp "$work/repacked.bin" "$output_logo"
truncate -s "$stock_size" "$output_logo"

"$mtklogo" unpack --config "$profile" --profile c110001 \
  --slots 0,38 --output "$verified" "$output_logo" >/dev/null
for slot_number in 000 038; do
  before="$unpacked/logo_${slot_number}_rgbabe.png"
  after="$verified/logo_${slot_number}_rgbabe.png"
  test -s "$before" && test -s "$after" || {
    echo "round-trip target slot missing: $((10#$slot_number))" >&2
    exit 7
  }
  before_hash=$(ffmpeg -hide_banner -loglevel error -i "$before" \
    -f framemd5 - | awk -F ', ' 'END { print $NF }')
  after_hash=$(ffmpeg -hide_banner -loglevel error -i "$after" \
    -f framemd5 - | awk -F ', ' 'END { print $NF }')
  test -n "$before_hash" && test "$before_hash" = "$after_hash" || {
    echo "round-trip target slot changed: $((10#$slot_number))" >&2
    exit 7
  }
done

for n in $(seq 1 37); do
  i=$(printf '%03d' "$n")
  before="$unpacked/logo_${i}_raw.z"
  after="$verified/logo_${i}_raw.z"
  test -s "$before" && test -s "$after" || {
    echo "round-trip raw slot missing: $i" >&2
    exit 8
  }
  cmp -s "$before" "$after" || {
    echo "non-target slot changed: $i" >&2
    exit 9
  }
done

echo "output=$output_logo"
echo "bytes=$(stat -c %s "$output_logo")"
echo "sha256=$(sha256sum "$output_logo" | awk '{print $1}')"
