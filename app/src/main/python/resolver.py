import yt_dlp
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://vk.com/",
    "Origin":  "https://vk.com",
}
def resolve(url):
    opts = {"quiet": True, "no_warnings": True, "skip_download": True,
            "format": "best[protocol^=m3u8]/best", "http_headers": HEADERS}
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        for f in info.get("formats", []):
            if f.get("protocol") == "m3u8" and f.get("url"):
                return f["url"]
        return info.get("url")
    except Exception as e:
        return "ERROR:" + str(e)
