#!/usr/bin/env sh
set -eu

ROOT=${ROOT:-/opt/oracle-voice/asr}
MODEL=${MODEL:-$ROOT/current-model}

find_one() {
  for p in "$@"; do
    if [ -f "$p" ]; then
      printf '%s\n' "$p"
      return 0
    fi
  done
  return 1
}

first_glob() {
  pattern=$1
  for p in $pattern; do
    if [ -f "$p" ]; then
      printf '%s\n' "$p"
      return 0
    fi
  done
  return 1
}

ENCODER=$(find_one \
  "$MODEL/encoder-epoch-99-avg-1.int8.onnx" \
  "$MODEL/encoder-epoch-99-avg-1.onnx" \
  "$MODEL/encoder.int8.onnx" \
  "$MODEL/encoder.onnx" \
  "$MODEL/model.int8.onnx" \
  "$MODEL/model.onnx" \
  || first_glob "$MODEL/*encoder*int8*.onnx" \
  || first_glob "$MODEL/*encoder*.onnx")

DECODER=$(find_one \
  "$MODEL/decoder-epoch-99-avg-1.onnx" \
  "$MODEL/decoder.onnx" \
  || first_glob "$MODEL/*decoder*.onnx")

JOINER=$(find_one \
  "$MODEL/joiner-epoch-99-avg-1.int8.onnx" \
  "$MODEL/joiner-epoch-99-avg-1.onnx" \
  "$MODEL/joiner.int8.onnx" \
  "$MODEL/joiner.onnx" \
  || first_glob "$MODEL/*joiner*int8*.onnx" \
  || first_glob "$MODEL/*joiner*.onnx")

TOKENS=$(find_one "$MODEL/tokens.txt" "$MODEL/tokens.txt.txt")
BPE=$(find_one "$MODEL/bpe.model" "$MODEL/bpe.vocab" || true)
if [ -z "${MODELING_UNIT:-}" ]; then
  if [ -n "$BPE" ]; then
    MODELING_UNIT=cjkchar+bpe
  else
    MODELING_UNIT=cjkchar
  fi
fi

echo "Using model: $MODEL"
echo "encoder: $ENCODER"
echo "decoder: $DECODER"
echo "joiner:  $JOINER"
echo "tokens:  $TOKENS"
if [ -n "$BPE" ]; then echo "bpe:     $BPE"; fi

ARGS="
  --host 0.0.0.0
  --port ${PORT:-10096}
  --encoder $ENCODER
  --decoder $DECODER
  --joiner $JOINER
  --tokens $TOKENS
  --num-threads ${NUM_THREADS:-2}
  --partial-interval-ms ${PARTIAL_INTERVAL_MS:-180}
  --modeling-unit $MODELING_UNIT
"

if [ -n "$BPE" ]; then
  ARGS="$ARGS --bpe-vocab $BPE"
fi

exec "$ROOT/.venv/bin/python" "$ROOT/funasr_sherpa_adapter.py" $ARGS
