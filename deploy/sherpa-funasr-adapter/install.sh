#!/usr/bin/env sh
set -eu

export SHLVL=1
unset BASH_ENV ENV PROMPT_COMMAND

ROOT=/opt/oracle-voice/asr
MODEL_DIR=${MODEL_DIR:-sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16}
MODEL_URL='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2'
MODEL_TAR=$ROOT/model.tar.bz2

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
