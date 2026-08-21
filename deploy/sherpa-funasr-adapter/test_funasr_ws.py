#!/usr/bin/env python3
import argparse
import asyncio
import json
import time
import wave

import websockets


def read_pcm16_wav(path):
    with wave.open(path, "rb") as w:
        if w.getnchannels() != 1:
            raise ValueError("wav must be mono")
        if w.getsampwidth() != 2:
            raise ValueError("wav must be PCM16")
        if w.getframerate() != 16000:
            raise ValueError("wav must be 16000 Hz")
        return w.readframes(w.getnframes())


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="ws://127.0.0.1:10096")
    parser.add_argument("--wav", required=True)
    parser.add_argument("--realtime", action="store_true")
    args = parser.parse_args()

    pcm = read_pcm16_wav(args.wav)
    started = time.time()

    async with websockets.connect(args.url, max_size=1 << 20) as ws:
        await ws.send(json.dumps({
            "mode": "2pass",
            "wav_name": "adapter-test",
            "wav_format": "pcm",
            "audio_fs": 16000,
            "is_speaking": True,
            "chunk_size": [5, 10, 5],
            "chunk_interval": 10,
            "itn": True,
        }))

        async def receiver():
            async for msg in ws:
                print(f"{time.time() - started:.3f}s {msg}")
                try:
                    obj = json.loads(msg)
                    if obj.get("is_final") or "offline" in obj.get("mode", ""):
                        return
                except Exception:
                    pass

        recv_task = asyncio.create_task(receiver())
        frame_bytes = 1920
        for offset in range(0, len(pcm), frame_bytes):
            await ws.send(pcm[offset:offset + frame_bytes])
            if args.realtime:
                await asyncio.sleep(0.060)

        await ws.send(json.dumps({"is_speaking": False}))
        await asyncio.wait_for(recv_task, timeout=15)


if __name__ == "__main__":
    asyncio.run(main())
