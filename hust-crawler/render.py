"""
Tải trang bằng trình duyệt thật, cho những link mà requests không lấy được.

Ba loại trang cần tới nó:
  * SPA — nội dung dựng bằng JavaScript, requests chỉ thấy khung rỗng
    (work.hust.edu.vn: cả trang chỉ có 1 thẻ href).
  * Trang chặn bot — trả 403 hoặc màn hình "đang kiểm tra trình duyệt".
  * Trang chứng chỉ TLS thiếu mắt xích — trình duyệt có kho CA riêng nên qua được.

Dùng Playwright thay vì Selenium: cùng ý tưởng (điều khiển Chromium thật) nhưng
không phải tự quản driver, và có sẵn image Docker kèm đủ thư viện hệ thống.
Thiếu Playwright thì module vẫn nạp được, chỉ báo không dùng được — crawler
không vì thế mà chết.

Đây là công cụ để đọc trang công khai mà JS mới dựng ra, không phải để vượt
đăng nhập hay giải captcha. Gặp captcha thật thì trả về nguyên trạng để tầng
trên ghi nhận là chặn.
"""
from __future__ import annotations

import re
import threading

# Dấu hiệu trang chặn bot: có thì HTML nhận được không phải nội dung thật
BLOCKED_MARKS = re.compile(
    r"cf-browser-verification|Just a moment|Checking your browser|"
    r"captcha|g-recaptcha|hcaptcha|Attention Required|DDoS protection",
    re.I,
)

# Playwright sync API gắn chặt với luồng đã tạo ra nó: dùng chung một browser
# giữa các luồng thì ném "cannot switch to a different thread". Nên mỗi luồng
# giữ browser riêng; render vốn hiếm nên vài trình duyệt cũng không tốn mấy.
_local = threading.local()
_all: list = []                    # để close() dọn được browser của mọi luồng
_reg_lock = threading.Lock()


def available() -> tuple[bool, str]:
    """(dùng được không, lý do nếu không)."""
    try:
        import playwright  # noqa: F401
    except ImportError:
        return False, "chưa cài playwright (pip install playwright && playwright install chromium)"
    return True, ""


def looks_blocked(status: int, html: str) -> bool:
    """Trang này có phải requests lấy hụt không?"""
    if status in (403, 429, 503):
        return True
    head = html[:20000]
    if BLOCKED_MARKS.search(head):
        return True
    # SPA: gần như không có link nào và chữ thì ít
    n_links = html.count("href=")
    text = re.sub(r"<[^>]+>", " ", html)
    return n_links <= 2 and len(text.split()) < 120


def _get_browser():
    """Browser của riêng luồng đang gọi, tạo một lần rồi dùng lại."""
    br = getattr(_local, "browser", None)
    if br is not None:
        return br
    from playwright.sync_api import sync_playwright
    pw = sync_playwright().start()
    br = pw.chromium.launch(
        args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-blink-features=AutomationControlled"]
    )
    _local.pw, _local.browser = pw, br
    with _reg_lock:
        _all.append((pw, br))
    return br


def fetch(url: str, timeout=30000, wait="networkidle", ua: str | None = None):
    """-> (html, status, lỗi). Trả ("", 0, lý do) nếu không dùng được."""
    ok, why = available()
    if not ok:
        return "", 0, why
    # Hai mức chờ. networkidle cho nội dung đầy đủ nhất, nhưng trang có quảng
    # cáo hay long-poll thì mạng không bao giờ lặng và sẽ timeout; trang tự
    # chuyển hướng thì content() nổ "page is navigating". Lùi về
    # domcontentloaded vẫn lấy được HTML đã dựng, chỉ thiếu phần tải muộn.
    err = ""
    for mode in (wait, "domcontentloaded"):
        # không cần khoá chung: mỗi luồng có browser riêng nên không đụng nhau
        try:
            ctx = _get_browser().new_context(
                user_agent=ua or ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                  "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"),
                locale="vi-VN",
                ignore_https_errors=True,      # site trường hay thiếu chứng chỉ trung gian
            )
            try:
                page = ctx.new_page()
                resp = page.goto(url, timeout=timeout, wait_until=mode)
                page.wait_for_timeout(600)     # chờ nốt phần render sau khi mạng lặng
                try:
                    html = page.content()
                except Exception:
                    page.wait_for_timeout(1200)   # đang chuyển hướng: chờ nó đứng yên
                    html = page.content()
                return html, (resp.status if resp else 0), ""
            finally:
                ctx.close()
        except Exception as e:
            err = f"{type(e).__name__}: {e}"
        if mode == "domcontentloaded":
            break
    return "", 0, err


def close():
    """Dọn browser của mọi luồng. Gọi lúc kết thúc mẻ crawl."""
    with _reg_lock:
        for pw, br in _all:
            try:
                br.close()
                pw.stop()
            except Exception:
                pass
        _all.clear()
