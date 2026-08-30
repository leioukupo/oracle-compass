# sherpa-onnx FunASR-compatible ASR adapter

This replaces a FunASR WebSocket server with a lighter sherpa-onnx backend while
keeping the client protocol used by the Android app:

- Client sends JSON start frame with `mode=2pass`, `wav_format=pcm`,
  `audio_fs=16000`, `is_speaking=true`.
- Client sends binary 16 kHz mono PCM16 little-endian audio frames.
- Client sends JSON `{"is_speaking": false}` to finish the utterance.
- Server returns FunASR-like JSON frames:
  `2pass-online` partials and `2pass-offline` final result.

## Install on your ASR host

```bash
mkdir -p /opt/oracle-voice/asr
cd /opt/oracle-voice/asr

# Copy these files into this directory first, then:
env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin SHLVL=1 \
  bash --noprofile --norc install.sh
```

The service listens on:

```text
ws://0.0.0.0:10096
http://0.0.0.0:10096/v1/audio/transcriptions
```

Set the Android app ASR URL to:

```text
ws://127.0.0.1:10096
```

If the Android device reaches the service through FRP, replace that with your public tunnel address.

The same port also exposes an OpenAI-compatible transcription endpoint used by
the Android app as the short final-ASR refinement pass. It accepts multipart
`model` and `file` fields and returns:

```json
{"text":"..."}
```

## Model choices

The default is the Chinese int8 model below. It replaces the old 14M model
while keeping the same WebSocket and HTTP adapter API:

- `sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30`
  Chinese only, larger than 14M, better accuracy, still suitable for CPU realtime.
- `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01`  
  Chinese only, still small, a bit better quality.
- `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16`  
  Bilingual, bigger, more general.

The official model page reports the 2025 Chinese int8 model at about 0.15 CPU
real-time factor in its example. The xlarge model is more accurate but is not
recommended for always-on CPU streaming on this host.

To install the default model on an existing adapter host, the old model
directory is left in place and only `current-model` is switched:

```bash
cd /opt/oracle-voice/asr
env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin SHLVL=1 \
  MODEL_DIR=sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30 \
  MODEL_URL='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2' \
  bash --noprofile --norc install.sh
```

To use another compatible model, pass its matching directory and archive URL:

```bash
MODEL_DIR=your-model-directory \
MODEL_URL='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/your-model.tar.bz2' \
  bash install.sh
```

The mirror page for the official ASR model family is:

`https://modelscope.cn/models/ZhaoChaoqun/sherpa-onnx-asr-models`

## Service commands

```bash
systemctl status oracle-sherpa-funasr
journalctl -u oracle-sherpa-funasr -f
systemctl restart oracle-sherpa-funasr
```

If systemd still references an old hard-coded model path, reinstall the service:

```bash
cd /opt/oracle-voice/asr
cp oracle-sherpa-funasr.service /etc/systemd/system/oracle-sherpa-funasr.service
systemctl daemon-reload
systemctl reset-failed oracle-sherpa-funasr
systemctl restart oracle-sherpa-funasr
```

## Test

Use a 16 kHz mono PCM WAV:

```bash
source /opt/oracle-voice/asr/.venv/bin/activate
python /opt/oracle-voice/asr/test_funasr_ws.py \
  --url ws://127.0.0.1:10096 \
  --wav /opt/oracle-voice/asr/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/test_wavs/0.wav

curl -sS -F model=paraformer \
  -F file=@/opt/oracle-voice/asr/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/test_wavs/0.wav \
  http://127.0.0.1:10096/v1/audio/transcriptions
```

## Notes

The adapter is CPU-first. Keep `NUM_THREADS=2` initially so it does not compete
with TTS or other services; increase it only after checking CPU load and ASR
latency. The launcher selects `cjkchar` for the 2025 model and
`cjkchar+bpe` when a model directory contains a BPE vocabulary. The old model
remains available for rollback:

```bash
cd /opt/oracle-voice/asr
env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin SHLVL=1 \
  MODEL_DIR=old-model-directory \
  bash --noprofile --norc install.sh
```

Official sherpa-onnx docs:
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
