#!/usr/bin/env python3
"""PhoneDrop - Android telefondan Mac'e (ve tersi) Wi-Fi uzerinden hizli dosya aktarimi.

Sadece Python standart kutuphanesini kullanir. Calistir:  python3 server.py
"""
import collections
import hashlib
import json
import os
import re
import secrets
import shlex
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import webbrowser
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("PHONEDROP_PORT", "8765"))
SAVE_DIR = Path(os.environ.get("PHONEDROP_DIR", str(Path.home() / "Downloads" / "PhoneDrop")))
PARTIAL_DIR = SAVE_DIR / ".partial"
OUTBOX_DIR = SAVE_DIR / "Telefona Gönder"
CONF_DIR = Path.home() / ".phonedrop"
WEB_DIR = Path(__file__).resolve().parent / "web"
APK_PATH = Path(__file__).resolve().parent / "PhoneDrop.apk"
APP_PACKAGE = "com.phonedrop.app"

MIN_CHUNK = 1 << 20
MAX_CHUNK = 64 << 20
PARTIAL_MAX_AGE = 7 * 24 * 3600

transfers = {}
transfers_lock = threading.Lock()
history = collections.deque(maxlen=50)
name_lock = threading.Lock()


def load_token():
    CONF_DIR.mkdir(parents=True, exist_ok=True)
    f = CONF_DIR / "token"
    if f.exists():
        t = f.read_text().strip()
        if len(t) >= 8:
            return t
    t = secrets.token_urlsafe(9)
    f.write_text(t)
    os.chmod(f, 0o600)
    return t


TOKEN = load_token()
# Android uygulamasi ag taramasinda dogru Mac'i bu parmak iziyle tanir (token'in kendisi yayinlanmaz)
FINGERPRINT = hashlib.sha256(TOKEN.encode()).hexdigest()[:12]


def computer_name():
    try:
        n = subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True, timeout=2).stdout.strip()
        if n:
            return n
    except Exception:
        pass
    return socket.gethostname().split(".")[0]


def advertise():
    """Bonjour ile agda duyur; bu surec olurse dns-sd de kapanir."""
    cmd = ('dns-sd -R %s _phonedrop._tcp local %d fp=%s >/dev/null 2>&1 & P=$!; '
           'while kill -0 %d 2>/dev/null; do sleep 2; done; kill $P'
           % (shlex.quote("PhoneDrop - " + computer_name()), PORT, FINGERPRINT, os.getpid()))
    try:
        subprocess.Popen(["/bin/sh", "-c", cmd], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127."):
            return ip
    except OSError:
        pass
    for ifc in ("en0", "en1", "bridge100"):
        try:
            ip = subprocess.run(["ipconfig", "getifaddr", ifc], capture_output=True, text=True, timeout=2).stdout.strip()
            if ip:
                return ip
        except Exception:
            pass
    return "127.0.0.1"


def phone_url():
    return "http://%s:%d/?t=%s" % (lan_ip(), PORT, TOKEN)


def notify(msg):
    script = "display notification %s with title \"PhoneDrop\" sound name \"Glass\"" % json.dumps(msg, ensure_ascii=False)
    try:
        subprocess.Popen(["osascript", "-e", script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def safe_name(name):
    name = os.path.basename(str(name).replace("\\", "/"))
    name = re.sub(r"[\x00-\x1f/:]", "_", name).strip().lstrip(".")
    if not name:
        name = "dosya"
    if len(name.encode()) > 200:
        stem, ext = os.path.splitext(name)
        name = stem.encode()[:180].decode(errors="ignore") + ext[:20]
    return name


def unique_path(directory, name):
    p = directory / name
    stem, ext = os.path.splitext(name)
    n = 1
    while p.exists():
        p = directory / ("%s (%d)%s" % (stem, n, ext))
        n += 1
    return p


class Transfer:
    def __init__(self, tid, name, size, mtime, chunk, done=()):
        self.id = tid
        self.name = name
        self.size = size
        self.mtime = mtime
        self.chunk = chunk
        self.done = set(done)
        self.live = 0
        self.lock = threading.Lock()
        self.samples = collections.deque(maxlen=400)
        self.updated = time.time()
        self.part = PARTIAL_DIR / (tid + ".part")
        self.meta = PARTIAL_DIR / (tid + ".json")

    @property
    def nchunks(self):
        return (self.size + self.chunk - 1) // self.chunk

    def chunk_len(self, i):
        return min(self.chunk, self.size - i * self.chunk)

    def done_bytes(self):
        return sum(self.chunk_len(i) for i in self.done)

    def save_meta(self):
        tmp = self.meta.with_suffix(".tmp")
        tmp.write_text(json.dumps({"name": self.name, "size": self.size, "mtime": self.mtime,
                                   "chunk": self.chunk, "done": sorted(self.done)}))
        os.replace(tmp, self.meta)

    def speed(self):
        now = time.time()
        return sum(n for t, n in self.samples if now - t < 3) / 3.0

    @classmethod
    def load(cls, tid):
        meta = PARTIAL_DIR / (tid + ".json")
        part = PARTIAL_DIR / (tid + ".part")
        if not (meta.exists() and part.exists()):
            return None
        try:
            m = json.loads(meta.read_text())
            return cls(tid, m["name"], m["size"], m["mtime"], m["chunk"], m["done"])
        except Exception:
            return None


def get_transfer(tid):
    if not re.fullmatch(r"[0-9a-f]{32}", tid or ""):
        return None
    with transfers_lock:
        t = transfers.get(tid)
        if t is None:
            t = Transfer.load(tid)
            if t:
                transfers[tid] = t
        return t


def cleanup_partials():
    now = time.time()
    for f in PARTIAL_DIR.glob("*"):
        try:
            if now - f.stat().st_mtime > PARTIAL_MAX_AGE:
                f.unlink()
        except OSError:
            pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PhoneDrop/1.0"

    def log_message(self, fmt, *args):
        pass

    # ---------- yardimcilar ----------
    def is_local(self):
        return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    def authed(self):
        if self.is_local():
            return True
        c = cookies.SimpleCookie()
        try:
            c.load(self.headers.get("Cookie", ""))
        except cookies.CookieError:
            return False
        return "pd" in c and secrets.compare_digest(c["pd"].value, TOKEN)

    def send_body(self, code, body, ctype="application/json; charset=utf-8", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_json(self, obj, code=200):
        self.send_body(code, json.dumps(obj, ensure_ascii=False).encode())

    def fail(self, code, msg):
        # Govdeyi okumadiysak baglantiyi kapat ki keep-alive bozulmasin
        self.close_connection = True
        self.send_json({"error": msg}, code)

    def read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 65536:
            raise ValueError("govde cok buyuk")
        return json.loads(self.rfile.read(n) or b"{}")

    def parse(self):
        u = urllib.parse.urlsplit(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        return u.path, q

    def serve_static(self, name, ctype):
        p = WEB_DIR / name
        self.send_body(200, p.read_bytes(), ctype)

    def serve_phone(self):
        host = (self.headers.get("Host") or "").rsplit(":", 1)[0] or lan_ip()
        q = urllib.parse.urlencode({"h": host, "p": PORT, "t": TOKEN})
        fallback = urllib.parse.quote("http://%s:%d/app.apk" % (host, PORT), safe="")
        pair = "intent://pair?%s#Intent;scheme=phonedrop;package=%s;S.browser_fallback_url=%s;end" % (q, APP_PACKAGE, fallback)
        html = (WEB_DIR / "phone.html").read_text()
        html = html.replace("{{PAIR_URL}}", pair).replace("{{HAS_APK}}", "1" if APK_PATH.exists() else "")
        self.send_body(200, html.encode(), "text/html; charset=utf-8")

    def serve_file(self, path, download_name, ctype="application/octet-stream"):
        size = path.stat().st_size
        start, end, code = 0, size - 1, 200
        rng = self.headers.get("Range")
        if rng and size:
            m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip())
            if m and (m.group(1) or m.group(2)):
                if m.group(1):
                    start = int(m.group(1))
                    end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
                else:
                    start = max(0, size - int(m.group(2)))
                if start > end or start >= size:
                    self.send_body(416, b"", headers={"Content-Range": "bytes */%d" % size})
                    return
                code = 206
        length = end - start + 1 if size else 0
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        ascii_name = download_name.encode("ascii", "replace").decode().replace('"', "_")
        self.send_header("Content-Disposition", "attachment; filename=\"%s\"; filename*=UTF-8''%s"
                         % (ascii_name, urllib.parse.quote(download_name)))
        if code == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if self.command == "HEAD" or not length:
            return
        with open(path, "rb") as f:
            self.wfile.flush()
            self.connection.sendfile(f, start, length)

    # ---------- GET ----------
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path, q = self.parse()
        if path == "/":
            t = q.get("t")
            if t and secrets.compare_digest(t, TOKEN):
                self.send_body(302, b"", "text/plain", {
                    "Location": "/",
                    "Set-Cookie": "pd=%s; Max-Age=31536000; Path=/; HttpOnly; SameSite=Lax" % TOKEN})
                return
            if not self.authed():
                self.send_body(403, "<meta name=viewport content='width=device-width'><h2 style='font-family:sans-serif'>"
                               "Erişim yok. Mac ekranındaki QR kodu telefonla tekrar okut.</h2>".encode(),
                               "text/html; charset=utf-8")
                return
            return self.serve_phone()
        if path == "/host":
            if not self.is_local():
                return self.fail(403, "yalnizca Mac")
            return self.serve_static("host.html", "text/html; charset=utf-8")
        if path == "/static/qrcode.js":
            return self.serve_static("qrcode.js", "text/javascript; charset=utf-8")
        if path == "/api/status":
            if not self.is_local():
                return self.fail(403, "yalnizca Mac")
            with transfers_lock:
                active = [{"id": t.id, "name": t.name, "size": t.size,
                           "received": t.done_bytes() + t.live, "speed": t.speed()}
                          for t in transfers.values() if time.time() - t.updated < 30]
            return self.send_json({"url": phone_url(), "saveDir": str(SAVE_DIR),
                                   "active": active, "history": list(history)})
        if not self.authed():
            return self.fail(403, "yetkisiz")
        if path == "/api/ping":
            return self.send_json({"ok": True, "name": computer_name(), "fp": FINGERPRINT})
        if path == "/app.apk":
            if not APK_PATH.exists():
                return self.fail(404, "apk yok")
            return self.serve_file(APK_PATH, "PhoneDrop.apk", "application/vnd.android.package-archive")
        if path == "/api/outbox":
            files = []
            for f in OUTBOX_DIR.iterdir():
                if f.is_file() and not f.name.startswith("."):
                    st = f.stat()
                    files.append({"name": f.name, "size": st.st_size, "mtime": st.st_mtime})
            files.sort(key=lambda x: -x["mtime"])
            return self.send_json({"files": files})
        if path.startswith("/dl/"):
            name = urllib.parse.unquote(path[4:])
            p = (OUTBOX_DIR / name).resolve()
            if p.parent != OUTBOX_DIR.resolve() or not p.is_file():
                return self.fail(404, "dosya yok")
            return self.serve_file(p, p.name)
        self.fail(404, "bulunamadi")

    # ---------- POST ----------
    def do_POST(self):
        path, q = self.parse()
        if path.startswith("/api/host/"):
            if not self.is_local():
                return self.fail(403, "yalnizca Mac")
            action = path[len("/api/host/"):]
            if action == "open":
                subprocess.Popen(["open", str(SAVE_DIR)])
            elif action == "outbox":
                subprocess.Popen(["open", str(OUTBOX_DIR)])
            elif action == "reveal":
                p = (SAVE_DIR / q.get("name", "")).resolve()
                if p.parent == SAVE_DIR.resolve() and p.exists():
                    subprocess.Popen(["open", "-R", str(p)])
            elif action == "quit":
                self.send_json({"ok": True})
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            return self.send_json({"ok": True})

        if not self.authed():
            return self.fail(403, "yetkisiz")

        if path == "/api/init":
            try:
                d = self.read_json()
                name = safe_name(d["name"])
                size = int(d["size"])
                mtime = float(d.get("mtime") or 0) / 1000.0
                chunk = int(d.get("chunk") or 8 << 20)
            except (ValueError, KeyError, TypeError):
                return self.fail(400, "gecersiz istek")
            if size < 0 or not (MIN_CHUNK <= chunk <= MAX_CHUNK):
                return self.fail(400, "gecersiz boyut")
            # Ayni dosya tekrar secilirse ayni id -> kaldigi yerden devam
            tid = hashlib.sha1(("%s|%d|%d|%d" % (name, size, int(mtime * 1000), chunk)).encode()).hexdigest()[:32]
            t = get_transfer(tid)
            if t is None:
                t = Transfer(tid, name, size, mtime, chunk)
                with open(t.part, "wb") as f:
                    f.truncate(size)
                t.save_meta()
                with transfers_lock:
                    transfers[tid] = t
            t.updated = time.time()
            return self.send_json({"id": tid, "done": sorted(t.done), "chunk": t.chunk})

        if path == "/api/finish":
            t = get_transfer(q.get("id"))
            if t is None:
                return self.fail(404, "aktarim bulunamadi")
            with t.lock:
                missing = t.nchunks - len(t.done)
                if missing:
                    return self.fail(409, "%d parca eksik" % missing)
                if t.part.stat().st_size != t.size:
                    return self.fail(500, "boyut uyusmuyor")
                with name_lock:
                    final = unique_path(SAVE_DIR, t.name)
                    os.replace(t.part, final)
                if t.mtime > 0:
                    try:
                        os.utime(final, (t.mtime, t.mtime))
                    except OSError:
                        pass
                try:
                    t.meta.unlink()
                except OSError:
                    pass
            with transfers_lock:
                transfers.pop(t.id, None)
            history.appendleft({"name": final.name, "size": t.size, "time": time.time()})
            notify("%s alındı (%s)" % (final.name, human(t.size)))
            print("  ✓ %s  (%s)" % (final.name, human(t.size)), flush=True)
            return self.send_json({"ok": True, "name": final.name})

        if path == "/api/cancel":
            t = get_transfer(q.get("id"))
            if t:
                with transfers_lock:
                    transfers.pop(t.id, None)
                for f in (t.part, t.meta):
                    try:
                        f.unlink()
                    except OSError:
                        pass
            return self.send_json({"ok": True})

        self.fail(404, "bulunamadi")

    # ---------- PUT (parca yukleme) ----------
    def do_PUT(self):
        path, q = self.parse()
        if path != "/api/chunk":
            return self.fail(404, "bulunamadi")
        if not self.authed():
            return self.fail(403, "yetkisiz")
        t = get_transfer(q.get("id"))
        if t is None:
            return self.fail(404, "aktarim bulunamadi")
        try:
            i = int(q.get("i", "-1"))
            n = int(self.headers.get("Content-Length") or -1)
        except ValueError:
            return self.fail(400, "gecersiz")
        if not (0 <= i < t.nchunks) or n != t.chunk_len(i):
            return self.fail(400, "parca boyutu hatali")

        written = 0
        try:
            with open(t.part, "r+b") as f:
                f.seek(i * t.chunk)
                while written < n:
                    buf = self.rfile.read(min(1 << 20, n - written))
                    if not buf:
                        raise IOError("baglanti koptu")
                    f.write(buf)
                    written += len(buf)
                    now = time.time()
                    with t.lock:
                        t.live += len(buf)
                        t.samples.append((now, len(buf)))
                        t.updated = now
        except (IOError, OSError, ConnectionError):
            with t.lock:
                t.live -= written
            self.close_connection = True
            return
        with t.lock:
            t.live -= written
            t.done.add(i)
            t.save_meta()
        self.send_json({"ok": True})


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%.1f %s" % (n, unit)) if unit != "B" else "%d B" % n
        n /= 1024.0


def main():
    for d in (SAVE_DIR, PARTIAL_DIR, OUTBOX_DIR):
        d.mkdir(parents=True, exist_ok=True)
    cleanup_partials()
    open_browser = "--no-browser" not in sys.argv

    try:
        server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    except OSError:
        print("PhoneDrop zaten çalışıyor gibi görünüyor, panel açılıyor.")
        if open_browser:
            webbrowser.open("http://localhost:%d/host" % PORT)
        return
    server.daemon_threads = True
    # Aktarim surerken Mac uykuya gecmesin
    try:
        subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())])
    except Exception:
        pass

    advertise()

    print("\n  PhoneDrop çalışıyor")
    print("  Telefonda aç : %s" % phone_url())
    print("  Mac paneli   : http://localhost:%d/host" % PORT)
    print("  Kayıt yeri   : %s" % SAVE_DIR)
    print("  Durdurmak için Ctrl+C\n", flush=True)
    if open_browser:
        webbrowser.open("http://localhost:%d/host" % PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    print("PhoneDrop kapatıldı.")


if __name__ == "__main__":
    main()
