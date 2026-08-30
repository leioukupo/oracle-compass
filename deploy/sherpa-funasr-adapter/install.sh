#!/usr/bin/env sh
set -eu

export SHLVL=1
unset BASH_ENV ENV PROMPT_COMMAND

ROOT=/opt/oracle-voice/asr
MODEL_DIR=${MODEL_DIR:-sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30}
MODEL_URL=${MODEL_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2}
MODEL_TAR=$ROOT/$(basename "$MODEL_URL")

mkdir -p "$ROOT"
cd "$ROOT"

if [ ! -d "$ROOT/.venv" ]; then
  python3 -m venv "$ROOT/.venv"
fi
. "$ROOT/.venv/bin/activate"
python -m pip install -U pip wheel
python -m pip install -U --no-cache-dir aiohttp numpy websockets sherpa-onnx

if [ ! -d "$ROOT/$MODEL_DIR" ]; then
  wget -c "$MODEL_URL" -O "$MODEL_TAR"
  tar xf "$MODEL_TAR"
fi

test -f "$ROOT/$MODEL_DIR/tokens.txt"
test -f "$ROOT/$MODEL_DIR/decoder.onnx" || test -f "$ROOT/$MODEL_DIR/decoder-epoch-99-avg-1.onnx"
test -f "$ROOT/$MODEL_DIR/joiner.int8.onnx" \
  || test -f "$ROOT/$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx" \
  || test -f "$ROOT/$MODEL_DIR/joiner-epoch-99-avg-1.onnx"
test -f "$ROOT/$MODEL_DIR/encoder.int8.onnx" \
  || test -f "$ROOT/$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx" \
  || test -f "$ROOT/$MODEL_DIR/encoder-epoch-99-avg-1.onnx"

ln -sfn "$ROOT/$MODEL_DIR" "$ROOT/current-model"

chmod +x "$ROOT/start_adapter.sh"
cp "$ROOT/oracle-sherpa-funasr.service" /etc/systemd/system/oracle-sherpa-funasr.service
systemctl daemon-reload
systemctl enable oracle-sherpa-funasr
systemctl restart oracle-sherpa-funasr

echo
echo "Installed."
echo "Check status:"
echo "  systemctl status oracle-sherpa-funasr"
echo "Follow logs:"
echo "  journalctl -u oracle-sherpa-funasr -f"
