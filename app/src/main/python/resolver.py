import socket
import time
import urllib.request
import threading
import yt_dlp
import re

DOH_URL = "https://dns.adguard-dns.com/dns-query"
DOH_HOSTS = {"dns.adguard-dns.com", "dns.google", "cloudflare-dns.com", "dns.quad9.net"}
DOH_TIMEOUT = 1.5
DOH_SLOW_THRESHOLD = 1.2
DNS_CACHE = {}
MAX_CACHE_SIZE = 100
DNS_TTL = 300
_doh_fail_until = 0.0
_doh_consecutive_fails = 0
_DOH_MAX_FAILS = 3
_DOH_COOLDOWN = 60
_doh_enabled = True
_doh_slow_count = 0
_doh_resolved_count = 0
_system_fallback_count = 0

_lock = threading.Lock()

try:
    import dns.message
    import dns.rdatatype
    _DNS_AVAILABLE = True
except Exception:
    _DNS_AVAILABLE = False

_orig_getaddrinfo = socket.getaddrinfo

def set_doh_enabled(enabled):
    global _doh_enabled, _doh_slow_count, _doh_consecutive_fails
    with _lock:
        _doh_enabled = bool(enabled)
        if not _doh_enabled:
            DNS_CACHE.clear()
            _doh_slow_count = 0
            _doh_consecutive_fails = 0

def _doh_lookup(host, timeout=DOH_TIMEOUT):
    q = dns.message.make_query(host, dns.rdatatype.A)
    req = urllib.request.Request(
        DOH_URL,
        data=q.to_wire(),
        headers={
            "Content-Type": "application/dns-message",
            "Accept": "application/dns-message",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        wire = resp.read()
    msg = dns.message.from_wire(wire)
    ips = []
    if msg.answer:
        for rrset in msg.answer:
            for rr in rrset:
                ips.append(str(rr))
    return ips

def _doh_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    global _doh_fail_until, _doh_consecutive_fails, _doh_slow_count, _doh_resolved_count, _system_fallback_count
    with _lock:
        enabled = _doh_enabled
        fail_until = _doh_fail_until

    if not enabled or not _DNS_AVAILABLE or host in DOH_HOSTS:
        with _lock:
            _system_fallback_count += 1
        return _orig_getaddrinfo(host, port, family, type, proto, flags)
    if family not in (0, socket.AF_INET):
        return _orig_getaddrinfo(host, port, family, type, proto, flags)
    if time.time() < fail_until:
        with _lock:
            _system_fallback_count += 1
        return _orig_getaddrinfo(host, port, family, type, proto, flags)
    now = time.time()
    cached = DNS_CACHE.get(host)
    if cached and (now - cached[1]) < DNS_TTL:
        ips = cached[0]
    else:
        try:
            started = time.time()
            ips = _doh_lookup(host)
            elapsed = time.time() - started
            with _lock:
                if elapsed > DOH_SLOW_THRESHOLD:
                    _doh_slow_count += 1
                    if _doh_slow_count >= 3:
                        _doh_fail_until = time.time() + _DOH_COOLDOWN
                else:
                    _doh_slow_count = 0
                if not ips:
                    _doh_consecutive_fails = 0
                    _system_fallback_count += 1
                    return _orig_getaddrinfo(host, port, family, type, proto, flags)
                if len(DNS_CACHE) >= MAX_CACHE_SIZE:
                    oldest_key = next(iter(DNS_CACHE))
                    del DNS_CACHE[oldest_key]
                DNS_CACHE[host] = (ips, now)
                _doh_consecutive_fails = 0
                _doh_resolved_count += 1
        except Exception:
            with _lock:
                _doh_consecutive_fails += 1
                if _doh_consecutive_fails >= _DOH_MAX_FAILS:
                    _doh_fail_until = time.time() + _DOH_COOLDOWN
                _system_fallback_count += 1
            return _orig_getaddrinfo(host, port, family, type, proto, flags)
    result = []
    for ip in ips:
        try:
            result.append((socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port)))
        except Exception:
            pass
    if not result:
        with _lock:
            _system_fallback_count += 1
        return _orig_getaddrinfo(host, port, family, type, proto, flags)
    return result

socket.getaddrinfo = _doh_getaddrinfo

def doh_health():
    with _lock:
        return {
            "available": _DNS_AVAILABLE,
            "enabled": _doh_enabled,
            "consecutive_fails": _doh_consecutive_fails,
            "cooldown_active": time.time() < _doh_fail_until,
            "cooldown_remaining": max(0, int(_doh_fail_until - time.time())),
            "cache_size": len(DNS_CACHE),
            "doh_resolved": _doh_resolved_count,
            "system_fallback": _system_fallback_count,
            "slow_count": _doh_slow_count,
        }

socket.setdefaulttimeout(15)
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://vk.com/",
    "Origin":  "https://vk.com",
}
def resolve(url):
    try:
        opts = {"quiet": True, "no_warnings": True, "skip_download": True,
                "http_headers": HEADERS, "socket_timeout": 15}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        if info.get("url") and ".m3u8" in info["url"]:
            return info["url"]
        formats = info.get("formats", [])
        for f in formats:
            if f.get("protocol") == "m3u8":
                u = f.get("url", "")
                if "master" in u or "index.m3u8" in u:
                    return u
        best = None; max_h = -1
        for f in formats:
            if f.get("protocol") == "m3u8":
                h = f.get("height") or 0
                if h > max_h: max_h = h; best = f
        if best: return best["url"]
        return info.get("url") or "ERROR: no m3u8 found"
    except Exception as e:
        return "ERROR[" + type(e).__name__ + "]: " + str(e)
def get_thumbnail(url):
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=15) as response:
            html = response.read().decode('utf-8', errors='ignore')
            match = re.search(r'<meta property="og:image" content="([^"]+)"', html)
            if match:
                return match.group(1)
    except Exception:
        pass
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "skip_download": True,
                               "http_headers": HEADERS, "socket_timeout": 15}) as ydl:
            info = ydl.extract_info(url, download=False)
            return info.get("thumbnail", "")
    except Exception:
        return ""
