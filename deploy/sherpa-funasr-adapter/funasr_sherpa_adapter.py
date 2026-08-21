#!/usr/bin/env python3
import argparse
import asyncio
import io
import json
import logging
import signal
import time
import wave
from pathlib import Path

from aiohttp import web
import numpy as np
import sherpa_onnx


SAMPLE_RATE = 16000


def pcm16_to_float32(data: bytes) -> np.ndarray:
    if not data:
        return np.array([], dtype=np.float32)
    if len(data) % 2:
        data = data[:-1]
    return np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0


def wav_or_pcm_to_float32(data: bytes) -> np.ndarray:
    if not data:
        return np.array([], dtype=np.float32)
    if data[:4] != b"RIFF":
        return pcm16_to_float32(data)
    with wave.open(io.BytesIO(data), "rb") as w:
        channels = w.getnchannels()
        rate = w.getframerate()
        width = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width != 2:
        raise ValueError(f"unsupported wav sample width: {width}")
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    if channels > 1:
        samples = samples.reshape((-1, channels)).mean(axis=1)
    samples = samples / 32768.0
    if rate != SAMPLE_RATE and samples.size:
        old_x = np.linspace(0.0, 1.0, num=samples.size, endpoint=False)
        new_size = max(1, int(samples.size * SAMPLE_RATE / rate))
        new_x = np.linspace(0.0, 1.0, num=new_size, endpoint=False)
        samples = np.interp(new_x, old_x, samples).astype(np.float32)
    return samples.astype(np.float32, copy=False)


def result_text(recognizer, stream) -> str:
    try:
        result = recognizer.get_result(stream)
    except AttributeError:
        result = getattr(stream, "result", "")
    if hasattr(result, "text"):
        return (result.text or "").strip()
    return str(result or "").strip()


def decode_one(recognizer, stream) -> None:
    if hasattr(recognizer, "decode_stream"):
        recognizer.decode_stream(stream)
    else:
        recognizer.decode_streams([stream])


def create_recognizer(args):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=args.tokens,
        encoder=args.encoder,
        decoder=args.decoder,
        joiner=args.joiner,
        num_threads=args.num_threads,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        decoding_method=args.decoding_method,
        max_active_paths=args.num_active_paths,
        enable_endpoint_detection=True,
        rule1_min_trailing_silence=args.rule1_min_trailing_silence,
        rule2_min_trailing_silence=args.rule2_min_trailing_silence,
        rule3_min_utterance_length=args.rule3_min_utterance_length,
        provider=args.provider,
        modeling_unit=args.modeling_unit,
        bpe_vocab=args.bpe_vocab,
    )


class FunAsrCompatServer:
    def __init__(self, recognizer, partial_interval_ms: int, decode_lock: asyncio.Lock):
        self.recognizer = recognizer
        self.partial_interval = partial_interval_ms / 1000.0
        self.decode_lock = decode_lock

    async def decode_ready(self, stream) -> None:
        async with self.decode_lock:
            while self.recognizer.is_ready(stream):
                await asyncio.to_thread(decode_one, self.recognizer, stream)

    async def send_json(self, ws, data) -> None:
        if hasattr(ws, "send_str"):
            await ws.send_str(json.dumps(data, ensure_ascii=False))
        else:
            await ws.send(json.dumps(data, ensure_ascii=False))

    async def send_partial_if_changed(self, ws, stream, state, force=False) -> None:
        text = result_text(self.recognizer, stream)
        now = time.monotonic()
        if not text:
            return
        if text == state["last_partial"] and not force:
            return
        if not force and now - state["last_partial_at"] < self.partial_interval:
            return
        state["last_partial"] = text
        state["last_partial_at"] = now
        await self.send_json(ws, {
            "is_final": False,
            "mode": "2pass-online",
            "text": text,
            "wav_name": state["wav_name"],
        })

    async def finish_utterance(self, ws, stream, state) -> None:
        tail = np.zeros(int(SAMPLE_RATE * 0.30), dtype=np.float32)
        stream.accept_waveform(SAMPLE_RATE, tail)
        stream.input_finished()
        await self.decode_ready(stream)
        text = result_text(self.recognizer, stream)
        await self.send_json(ws, {
            "is_final": True,
            "mode": "2pass-offline",
            "text": text,
            "wav_name": state["wav_name"],
        })

    async def recognize_samples(self, samples: np.ndarray) -> str:
        stream = self.recognizer.create_stream()
        if samples.size:
            stream.accept_waveform(SAMPLE_RATE, samples)
        stream.input_finished()
        await self.decode_ready(stream)
        return result_text(self.recognizer, stream)

    async def handle_ws(self, request):
        ws = web.WebSocketResponse(max_msg_size=request.app["max_size"])
        await ws.prepare(request)
        remote = request.remote
        logging.info("client connected: %s", remote)
        stream = self.recognizer.create_stream()
        state = {
            "wav_name": f"sherpa-{int(time.time() * 1000)}",
            "last_partial": "",
            "last_partial_at": 0.0,
            "started": False,
            "finished": False,
        }

        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    message = msg.data
                elif msg.type == web.WSMsgType.BINARY:
                    message = msg.data
                elif msg.type in (web.WSMsgType.CLOSE, web.WSMsgType.CLOSED, web.WSMsgType.ERROR):
                    break
                else:
                    continue
                if isinstance(message, str):
                    try:
                        obj = json.loads(message)
                    except json.JSONDecodeError:
                        if message == "Done":
                            await self.finish_utterance(ws, stream, state)
                            return
                        continue

                    if obj.get("wav_name"):
                        state["wav_name"] = str(obj.get("wav_name"))
                    if obj.get("is_speaking") is True:
                        state["started"] = True
                        continue
                    if obj.get("is_speaking") is False:
                        if not state["finished"]:
                            state["finished"] = True
                            await self.finish_utterance(ws, stream, state)
                        return
                    continue

                samples = pcm16_to_float32(message)
                if samples.size == 0:
                    continue
                stream.accept_waveform(SAMPLE_RATE, samples)
                await self.decode_ready(stream)
                await self.send_partial_if_changed(ws, stream, state)

        except Exception:
            logging.exception("connection failed")
            try:
                await self.send_json(ws, {
                    "is_final": True,
                    "mode": "2pass-offline",
                    "text": "",
                    "wav_name": state["wav_name"],
                })
            except Exception:
                pass
        finally:
            logging.info("client disconnected: %s", remote)
        return ws

    async def handle_transcriptions(self, request):
        started = time.monotonic()
        try:
            reader = await request.multipart()
            audio = b""
            model = ""
            async for part in reader:
                if part.name == "model":
                    model = (await part.text()).strip()
                elif part.name == "file":
                    audio = await part.read()
            samples = await asyncio.to_thread(wav_or_pcm_to_float32, audio)
            text = await self.recognize_samples(samples)
            logging.info("http transcription model=%s samples=%d ms=%.1f text=%s",
                         model, samples.size, (time.monotonic() - started) * 1000, text[:80])
            return web.json_response({"text": text}, dumps=lambda obj: json.dumps(obj, ensure_ascii=False))
        except Exception as exc:
            logging.exception("http transcription failed")
            return web.json_response({"error": str(exc), "text": ""}, status=500,
                                     dumps=lambda obj: json.dumps(obj, ensure_ascii=False))

    async def handle_health(self, request):
        return web.json_response({"ok": True, "service": "oracle-sherpa-funasr"})


def parse_args():
    parser = argparse.ArgumentParser(
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=10096)
    parser.add_argument("--encoder", required=True)
    parser.add_argument("--decoder", required=True)
    parser.add_argument("--joiner", required=True)
    parser.add_argument("--tokens", required=True)
    parser.add_argument("--bpe-vocab", default="")
    parser.add_argument("--provider", default="cpu")
    parser.add_argument("--num-threads", type=int, default=2)
    parser.add_argument("--num-active-paths", type=int, default=4)
    parser.add_argument("--decoding-method", default="greedy_search")
    parser.add_argument("--modeling-unit", default="cjkchar+bpe")
    parser.add_argument("--rule1-min-trailing-silence", type=float, default=1.8)
    parser.add_argument("--rule2-min-trailing-silence", type=float, default=0.8)
    parser.add_argument("--rule3-min-utterance-length", type=float, default=12.0)
    parser.add_argument("--partial-interval-ms", type=int, default=180)
    parser.add_argument("--max-size", type=int, default=1 << 20)
    parser.add_argument("--log-level", default="info")
    return parser.parse_args()


async def main():
    args = parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(message)s",
    )

    for p in [args.encoder, args.decoder, args.joiner, args.tokens]:
        if not Path(p).is_file():
            raise FileNotFoundError(p)

    logging.info("loading sherpa-onnx recognizer")
    recognizer = create_recognizer(args)
    server = FunAsrCompatServer(
        recognizer=recognizer,
        partial_interval_ms=args.partial_interval_ms,
        decode_lock=asyncio.Lock(),
    )

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:
            pass

    app = web.Application(client_max_size=args.max_size)
    app["max_size"] = args.max_size
    app.router.add_get("/", server.handle_ws)
    app.router.add_get("/ws", server.handle_ws)
    app.router.add_post("/v1/audio/transcriptions", server.handle_transcriptions)
    app.router.add_get("/health", server.handle_health)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, args.host, args.port)
    await site.start()
    logging.info("listening on ws/http://%s:%d", args.host, args.port)
    try:
        await stop.wait()
    finally:
        await runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
