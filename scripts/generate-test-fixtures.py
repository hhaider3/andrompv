#!/usr/bin/env python3
"""Run before connectedDebugAndroidTest. No public peers or remote media."""
from pathlib import Path
import hashlib
import subprocess

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/androidTest/assets"
assets.mkdir(parents=True, exist_ok=True)
subprocess.run([
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "color=c=teal:s=160x90:r=12:d=24",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=24",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "ultrafast",
    "-c:a", "aac", "-b:a", "32k", "-movflags", "+faststart", "-shortest",
    str(assets / "sample.mp4")
], check=True)
(assets / "sample.srt").write_text("1\n00:00:00,000 --> 00:00:24,000\nAndroid subtitle fixture\n", encoding="utf-8")

def bencode(value):
    if isinstance(value, int):
        return b"i" + str(value).encode() + b"e"
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, bytes):
        return str(len(value)).encode() + b":" + value
    if isinstance(value, list):
        return b"l" + b"".join(bencode(item) for item in value) + b"e"
    if isinstance(value, dict):
        return b"d" + b"".join(bencode(key) + bencode(value[key]) for key in sorted(value)) + b"e"
    raise TypeError(type(value))

media = (assets / "sample.mp4").read_bytes()
payload = media * 2
piece_length = 16384
info = {
    "name": "andrompv-local-test", "private": 1, "piece length": piece_length,
    "files": [{"length": len(media), "path": [name]} for name in ["Episode 10.mp4", "Episode 2.mp4"]],
    "pieces": b"".join(hashlib.sha1(payload[i:i + piece_length]).digest() for i in range(0, len(payload), piece_length)),
}
(assets / "sample.torrent").write_bytes(bencode({"info": info, "url-list": ["http://127.0.0.1:18765/"]}))
print(f"Generated Android test fixtures ({len(media)} byte video).")
