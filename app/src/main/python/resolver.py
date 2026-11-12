import yt_dlp
import urllib.request
import re
import socket

socket.setdefaulttimeout(15)

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
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
        return "ERROR: " + str(e)

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
