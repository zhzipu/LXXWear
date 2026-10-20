#!/usr/bin/env python3
"""LX-N-XW-Music 局域网模拟服务端。

只用于联调手表端：扫一个目录里的音频/歌词/封面，按 docs/LAN_PROTOCOL.md 提供
HTTP 接口，并定时发 UDP 广播让手表能自动发现。

用法:
    python tools/mock_server.py --media tools/mock/media
"""

import argparse
import hashlib
import json
import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

SERVICE = "lxnx-lan"
VERSION = 1
AUDIO_EXTS = (".mp3", ".m4a", ".flac", ".wav", ".aac", ".ogg")
CONTENT_TYPES = {
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".flac": "audio/flac",
    ".wav": "audio/wav",
    ".aac": "audio/aac",
    ".ogg": "audio/ogg",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".lrc": "text/plain; charset=utf-8",
}


class Library:
    """媒体目录索引。"""

    def __init__(self, media_dir):
        self.media_dir = media_dir
        self.playlist_id = hashlib.md5(os.path.abspath(media_dir).encode("utf-8")).hexdigest()[:8]
        self.playlist_name = os.path.basename(os.path.abspath(media_dir)) or "手机歌单"
        self.revision = ""
        self.songs = []
        self.by_id = {}
        self.reload()
        if not self.songs:
            print(f"[warn] {media_dir} 里没有找到音频文件（支持 {', '.join(AUDIO_EXTS)}）")

    def reload(self):
        self.songs.clear()
        self.by_id.clear()
        if not os.path.isdir(self.media_dir):
            return
        for name in sorted(os.listdir(self.media_dir)):
            stem, ext = os.path.splitext(name)
            if ext.lower() not in AUDIO_EXTS:
                continue
            audio = os.path.join(self.media_dir, name)
            if not os.path.isfile(audio):
                continue
            artist, title = split_name(stem)
            # id 必须是 [A-Za-z0-9_-]，中文名直接清洗会撞车，用文件名哈希
            song_id = hashlib.md5(name.encode("utf-8")).hexdigest()[:12]
            lrc = os.path.join(self.media_dir, stem + ".lrc")
            cover = None
            for cover_ext in (".jpg", ".jpeg", ".png"):
                candidate = os.path.join(self.media_dir, stem + cover_ext)
                if os.path.isfile(candidate):
                    cover = candidate
                    break
            entry = {
                "id": song_id,
                "name": title,
                "artist": artist,
                "album": "",
                "duration": 0,
                "audio": {
                    "ready": True,
                    "ext": ext.lstrip(".").lower(),
                    "size": os.path.getsize(audio),
                },
                "_audio_path": audio,
                "_lyric_path": lrc if os.path.isfile(lrc) else None,
                "_cover_path": cover,
            }
            self.songs.append(entry)
            self.by_id[song_id] = entry
        # 版本号取「歌单内容指纹」：文件不变就一直不变，手表据此判断要不要重新传
        fingerprint = "|".join(f"{entry['id']}:{entry['audio']['size']}" for entry in self.songs)
        self.revision = hashlib.md5(fingerprint.encode("utf-8")).hexdigest()[:12]
        print(f"[info] 已索引 {len(self.songs)} 首歌曲，revision={self.revision}")

    def playlist_json(self):
        songs = []
        for entry in self.songs:
            public = {k: v for k, v in entry.items() if not k.startswith("_")}
            songs.append(public)
        return {
            "playlistId": self.playlist_id,
            "playlistName": self.playlist_name,
            "revision": self.revision,
            "songs": songs,
        }


def split_name(stem):
    if " - " in stem:
        artist, _, title = stem.partition(" - ")
        return artist.strip(), title.strip()
    return "", stem.strip()


class Handler(BaseHTTPRequestHandler):
    server_version = "LXMock/1.0"
    protocol_version = "HTTP/1.1"

    @property
    def library(self):
        return self.server.library

    def log_message(self, fmt, *args):
        print(f"[http] {self.address_string()} {fmt % args}")

    # ---------- 路由 ----------

    def do_GET(self):
        if not self.authorized():
            return self.send_error(401)
        path = unquote(self.path.split("?", 1)[0])
        if path == "/api/ping":
            return self.send_json(self.ping_payload())
        if path == "/api/playlist":
            return self.send_json(self.library.playlist_json())
        if path.startswith("/api/audio/"):
            return self.send_audio(path[len("/api/audio/"):])
        if path.startswith("/api/lyric/"):
            return self.send_lyric(path[len("/api/lyric/"):])
        if path.startswith("/api/cover/"):
            return self.send_cover(path[len("/api/cover/"):])
        if path == "/":
            return self.send_json({"service": SERVICE, "songs": len(self.library.songs)})
        self.send_error(404)

    def do_POST(self):
        if not self.authorized():
            return self.send_error(401)
        path = unquote(self.path.split("?", 1)[0])
        if path.startswith("/api/prepare/"):
            song_id = path[len("/api/prepare/"):]
            entry = self.library.by_id.get(song_id)
            state = "ready" if entry else "failed"
            return self.send_json({"state": state})
        self.send_error(404)

    # ---------- 响应 ----------

    def authorized(self):
        token = getattr(self.server, "token", "")
        if not token:
            return True
        return self.headers.get("X-LX-Token") == token

    def ping_payload(self):
        return {
            "service": SERVICE,
            "version": VERSION,
            "name": self.server.device_name,
            "songs": len(self.library.songs),
        }

    def send_json(self, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_audio(self, song_id):
        entry = self.library.by_id.get(song_id)
        if not entry:
            return self.send_error(404)
        return self.send_file(entry["_audio_path"])

    def send_lyric(self, song_id):
        entry = self.library.by_id.get(song_id)
        if not entry or not entry["_lyric_path"]:
            return self.send_error(404)
        return self.send_file(entry["_lyric_path"])

    def send_cover(self, song_id):
        entry = self.library.by_id.get(song_id)
        if not entry or not entry["_cover_path"]:
            return self.send_error(404)
        return self.send_file(entry["_cover_path"])

    def send_file(self, path):
        size = os.path.getsize(path)
        start, end = 0, size - 1
        status = 200

        range_header = self.headers.get("Range")
        if range_header:
            match = re.match(r"bytes=(\d*)-(\d*)", range_header.strip())
            if match:
                first, last = match.group(1), match.group(2)
                if first:
                    start = int(first)
                if last:
                    end = min(int(last), size - 1)
                if start > end:
                    start, end = 0, size - 1
                    status = 200
                else:
                    status = 206

        length = end - start + 1
        ext = os.path.splitext(path)[1].lower()
        self.send_response(status)
        self.send_header("Content-Type", CONTENT_TYPES.get(ext, "application/octet-stream"))
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()

        with open(path, "rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


def beacon_loop(port, discovery_port, device_name, token="", interval=2.0):
    payload = json.dumps({
        "service": SERVICE,
        "version": VERSION,
        "port": port,
        "name": device_name,
        "token": token,
    }, ensure_ascii=False).encode("utf-8")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    while True:
        for target in ("255.255.255.255",):
            try:
                sock.sendto(payload, (target, discovery_port))
            except OSError as exc:
                print(f"[warn] 广播失败: {exc}")
        time.sleep(interval)


def main():
    parser = argparse.ArgumentParser(description="LX-N-XW-Music 局域网模拟服务端")
    parser.add_argument("--media", default=os.path.join("tools", "mock", "media"),
                        help="音频目录，默认 tools/mock/media")
    parser.add_argument("--port", type=int, default=8686, help="HTTP 端口，默认 8686")
    parser.add_argument("--discovery-port", type=int, default=8687, help="UDP 广播端口，默认 8687")
    parser.add_argument("--name", default=socket.gethostname(), help="显示在手表上的设备名")
    parser.add_argument("--token", default="",
                        help="配对 token，设置后广播里会带它，且所有请求必须带 X-LX-Token")
    args = parser.parse_args()

    library = Library(os.path.abspath(args.media))

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.library = library
    server.device_name = args.name
    server.token = args.token

    threading.Thread(target=beacon_loop,
                     args=(args.port, args.discovery_port, args.name, args.token),
                     daemon=True).start()

    local_ip = "127.0.0.1"
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        local_ip = probe.getsockname()[0]
        probe.close()
    except OSError:
        pass

    print(f"[info] HTTP  http://{local_ip}:{args.port}")
    print(f"[info] 广播  udp://255.255.255.255:{args.discovery_port} 每 2 秒")
    print("[info] 手表与电脑需在同一 Wi-Fi，防火墙放行 TCP 8686 / UDP 8687")
    print("[info] Ctrl+C 退出")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[info] 已退出")
    finally:
        server.server_close()


if __name__ == "__main__":
    sys.exit(main())
