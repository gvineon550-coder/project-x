import yt_dlp

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://vk.com/",
    "Origin":  "https://vk.com",
}

def resolve(url):
    errors = []
    for fmt in ["best[protocol^=m3u8]/best", "best", "all"]:
        try:
            opts = {
                "quiet": True, "no_warnings": True, "skip_download": True,
                "format": fmt, "http_headers": HEADERS,
            }
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)
            for f in info.get("formats", []):
                proto = f.get("protocol", "") or ""
                if proto.startswith("m3u8") and f.get("url"):
                    return f["url"]
            if info.get("url"):
                return info["url"]
            protocols = [f.get("protocol") for f in info.get("formats", [])]
            errors.append(f"{fmt}: url пустой, protocols={protocols}")
        except Exception as e:
            errors.append(f"{fmt}: {e}")
    return "ERROR: " + " || ".join(errors)
