import base64
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from bs4 import BeautifulSoup
from fastapi import HTTPException
from pydantic import ValidationError

sys.path.insert(0, str(Path(__file__).parents[1] / "api"))
import main  # noqa: E402


class ApiSchemaTest(unittest.TestCase):
    def test_outgoing_links_normalize_fallback_and_dedupe(self):
        body = BeautifulSoup(
            """
            <main>
              <a href="/admissions/#apply"> Thông   tin\n tuyển sinh </a>
              <a href="https://outside.example/a#copy">Ngoài trường</a>
              <a href="/same#first"></a>
              <a href="/same#second" title="  Mô tả   bổ sung "></a>
              <a href="javascript:alert(1)">Không giữ</a>
              <a href="mailto:test@example.com">Không giữ</a>
            </main>
            """,
            "lxml",
        ).main

        self.assertEqual(
            main.outgoing_links(body, "https://hust.edu.vn/news/post.html"),
            [
                {"url": "https://hust.edu.vn/admissions/", "text": "Thông tin tuyển sinh"},
                {"url": "https://outside.example/a", "text": "Ngoài trường"},
                {"url": "https://hust.edu.vn/same", "text": "Mô tả bổ sung"},
            ],
        )

    def test_extract_has_public_source_fields(self):
        html = """
        <html><head><title>Tiêu đề thử</title></head><body>
          <nav><a href="/menu">Không phải link trong bài</a></nav>
          <article class="bodytext">
            <p>Nội dung bài viết.</p>
            <a href="/x#section" aria-label="Liên kết X"></a>
          </article>
        </body></html>
        """
        rec = {
            "url": "https://hust.edu.vn/news/post.html",
            "status": 200,
            "encoding": "utf-8",
            "html_b64": base64.b64encode(html.encode()).decode(),
        }

        doc = main.extract(rec)
        self.assertEqual(doc["title"], "Tiêu đề thử")
        self.assertEqual(doc["text"], "Nội dung bài viết.")
        self.assertEqual(doc["outgoing_links"], [
            {"url": "https://hust.edu.vn/x", "text": "Liên kết X"}
        ])

    def test_public_document_validates_contract(self):
        doc = main.PublicDocument(
            url="https://fixture.local/doc",
            content="nội dung",
            outgoing_links=[{"url": "https://fixture.local/next", "text": "Đi tiếp"}],
        )
        self.assertEqual(main.lucene_document(doc)["text"], "nội dung")
        self.assertEqual(main.lucene_document(doc)["host"], "fixture.local")
        capped = main.PublicDocument(url="https://fixture.local/capped",
                                     title="a" * 501, content="b" * 200_001)
        self.assertEqual(len(capped.title), 500)
        self.assertEqual(len(capped.content), 200_000)
        with self.assertRaises(ValidationError):
            main.PublicDocument(url="javascript:alert(1)", title="x")
        with self.assertRaises(ValidationError):
            main.PublicDocument(url="https://fixture.local/empty")
        with self.assertRaises(ValidationError):
            main.PublicDocument(url="https://fixture.local/bad-date", title="x",
                                published_at="not-a-date")

    def test_fetch_returns_public_document_without_network(self):
        html = b"<html><head><title>Bai test</title></head><body><main>"
        html += b"<p>Noi dung bai.</p><a href='/next#part' title=' Mo ta next '></a>"
        html += b"</main></body></html>"

        class Response:
            def __init__(self, status=200, content=b"", url="", body=None):
                self.status_code = status
                self.content = content
                self.url = httpx.URL(url)
                self.encoding = "utf-8"
                self._body = body

            def raise_for_status(self):
                return None

            def json(self):
                return self._body

        posted = []

        class Client:
            def __init__(self, *args, base_url=None, **kwargs):
                self.base_url = base_url

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def get(self, url, **kwargs):
                if self.base_url is None:
                    return Response(content=html, url=url)
                return Response(url="http://lucene:8081/stats", body={"docs": 1})

            def post(self, url, **kwargs):
                posted.append(kwargs.get("json"))
                return Response(url="http://lucene:8081/bulk", body={"indexed": 1})

        main._lan_tai["luc"] = time.time()
        with patch.object(main.httpx, "Client", Client), patch.object(main, "_ghi_kho"):
            result = main.fetch_one(main.LayReq(url="https://fixture.local/article"))

        self.assertEqual(result["document"]["title"], "Bai test")
        self.assertEqual(result["document"]["content"], "Noi dung bai.")
        self.assertEqual(result["document"]["outgoing_links"], [
            {"url": "https://fixture.local/next", "text": "Mo ta next"}
        ])
        self.assertNotIn("outgoing_links", posted[0][0])
        self.assertIn("html", posted[0][0])

    def test_fetch_rejects_non_http_url(self):
        with self.assertRaises(HTTPException) as caught:
            main.fetch_one(main.LayReq(url="ftp://fixture.local/article"))
        self.assertEqual(caught.exception.status_code, 422)


if __name__ == "__main__":
    unittest.main()
