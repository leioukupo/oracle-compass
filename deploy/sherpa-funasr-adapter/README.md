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

## Faster model choices

For less download time, use a smaller ASR model:

- `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`  
  Chinese only, very small, fastest to pull.
- `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01`  
  Chinese only, still small, a bit better quality.
- `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16`  
  Bilingual, bigger, more general.

You can override the model tarball during install:

```bash
MODEL_URL='https://www.modelscope.cn/.../your-model.tar.bz2' bash install.sh
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

The default model is the int8 version of
`sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16`.
It is CPU-first and usually fast enough for always-on short utterances.

Official sherpa-onnx docs:
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
