# 语音重部署手册

这份手册记录当前这套语音链路的重装方式：

- TTS：CosyVoice，HTTP 端口 `8188`
- ASR 快速流式：sherpa-onnx FunASR 兼容层，WebSocket/HTTP 端口 `10096`
- ASR 最终校正：SenseVoice/FunASR 兼容 HTTP 服务，端口 `50000`

当前常用外网穿透端口：

- TTS `25304`
- ASR 快速 `15462`
- ASR 最终 `11608`

当前这套已验证地址示例：

- TTS 本地 `http://192.168.31.5:8188/`，公网 `http://114.66.28.184:25304/`
- ASR 快速本地 `ws://192.168.31.5:10096`，公网 `ws://114.66.28.184:15462`
- ASR 最终本地 `http://192.168.31.5:50000/`，公网 `http://114.66.28.184:11608/`

## 1. TTS：CosyVoice

### 1.1 启动约定

服务需要提供这两个接口：

- `GET /v1/voices`
- `POST /v1/audio/speech`

建议把服务挂在 `8188`，然后用 FRP 暴露到公网端口 `25304`。

### 1.2 推荐启动模板

把下面的 `<your-cosyvoice-image>` 换成你自己的镜像名即可：

```bash
docker run -d --name cosyvoice --restart unless-stopped \
  --gpus all \
  -p 8188:8188 \
  -v /opt/cosyvoice-cache:/root/.cache \
  -v /opt/cosyvoice-data:/data \
  <your-cosyvoice-image>
```

### 1.3 验证

```bash
curl http://127.0.0.1:8188/
curl http://127.0.0.1:8188/v1/voices
curl -H 'Content-Type: application/json' \
  -d '{"model":"cosyvoice","voice":"5f133e352183","input":"真理罗盘语音测试。","response_format":"wav"}' \
  http://127.0.0.1:8188/v1/audio/speech -o /tmp/tts.wav
file /tmp/tts.wav
```

### 1.4 常见问题

- `Voice not found`：先查 `GET /v1/voices`，优先用第一个 `custom_voices[].id`。
- App 里 `ttsUrl` 只填根地址即可，例如 `http://<host>:8188/`。
- 如果用了 FRP，就填公网地址，例如 `http://<public>:25304/`。

## 2. ASR 快速流式：sherpa-onnx adapter

仓库里已经带了完整脚本：

- `deploy/sherpa-funasr-adapter/install.sh`
- `deploy/sherpa-funasr-adapter/start_adapter.sh`
- `deploy/sherpa-funasr-adapter/oracle-sherpa-funasr.service`
- 详细说明：`deploy/sherpa-funasr-adapter/README.md`

### 2.1 安装

```bash
mkdir -p /opt/oracle-voice/asr
cd /opt/oracle-voice/asr

# 把 deploy/sherpa-funasr-adapter/ 里的文件放到这里后执行
bash install.sh
```

### 2.2 服务与测试

```bash
systemctl status oracle-sherpa-funasr
journalctl -u oracle-sherpa-funasr -f
systemctl restart oracle-sherpa-funasr

curl http://127.0.0.1:10096/health
curl -F model=paraformer \
  -F file=@sample.wav \
  http://127.0.0.1:10096/v1/audio/transcriptions
```

### 2.3 App 侧填写

- `ASR 地址`：`ws://<host>:10096` 或 `http://<host>:10096/`
- FRP 公网：`ws://<public>:15462`

### 2.4 FRP 示例

```toml
[[proxies]]
name = "asr_stream"
type = "tcp"
localIP = "127.0.0.1"
localPort = 10096
remotePort = 15462
```

## 3. ASR 最终校正：SenseVoice

### 3.1 启动约定

最终校正服务需要提供：

- `GET /docs`
- `GET /openapi.json`
- `POST /api/v1/asr`

建议本地端口 `50000`，FRP 公网端口 `11608`。

### 3.2 推荐启动模板

```bash
docker run -d --name sensevoice --restart unless-stopped \
  --gpus all \
  -p 50000:50000 \
  -v /opt/sensevoice-cache:/root/.cache \
  -v /opt/sensevoice-data:/data \
  <your-sensevoice-image>
```

### 3.3 验证

```bash
curl http://127.0.0.1:50000/docs
curl http://127.0.0.1:50000/openapi.json
curl -F 'files=@sample.wav;type=audio/wav' \
  -F 'keys=test.wav' \
  -F 'lang=auto' \
  -F 'use_itn=true' \
  http://127.0.0.1:50000/api/v1/asr
```

### 3.4 常见问题

- 如果出现 `SinusoidalPositionEncoder` / `_state_dict_pre_hooks` 报错，说明模型代码没初始化好。
- 你当前这套代码需要保留：
  ```python
  class SinusoidalPositionEncoder(torch.nn.Module):
      def __init__(self, d_model=80, dropout_rate=0.1):
          super().__init__()
  ```
- 如果你是重拉容器，记得清缓存后再起：
  ```bash
  rm -rf /root/.cache/modelscope /root/.cache/huggingface
  ```

### 3.5 FRP 示例

```toml
[[proxies]]
name = "asr_final"
type = "tcp"
localIP = "127.0.0.1"
localPort = 50000
remotePort = 11608
```

## 4. App 侧最终填写

- `TTS 地址`：`http://<host>:8188/`
- `ASR 地址`：`ws://<host>:10096` 或 `http://<host>:10096/`
- `最终 ASR`：`http://<host>:50000/`

如果走 FRP，就把 `<host>` 换成公网地址和对应远程端口。
