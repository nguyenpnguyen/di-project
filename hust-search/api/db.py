"""Kết nối MongoDB, lược đồ `$jsonSchema` và index.

Mongo là tầng lưu kết quả bóc tách nằm giữa kho thô của crawler và Lucene
(xem KE-HOACH-BOC-TACH.md mục 3, 6). Lược đồ được tạo lúc `api` khởi động;
mô tả từng trường ở SCHEMA.md.
"""
from __future__ import annotations

import os

MONGO_URL = os.getenv("MONGO_URL", "mongodb://mongo:27017")
DB_NAME = os.getenv("MONGO_DB", "hust")

_S = {"bsonType": "string"}
_N = {"bsonType": ["int", "long"], "minimum": 0}
_B = {"bsonType": "bool"}
_ARR_S = {"bsonType": "array", "items": _S}
_KIND = {"enum": ["page", "document", "image", "external"]}
_TYPE = {"enum": ["href", "embed"]}
_NGAY = {"bsonType": "string", "pattern": r"^(\d{4}-\d{2}-\d{2})?$"}

SCHEMAS: dict[str, dict] = {
    "pages": {
        "required": ["_id", "host", "title", "content", "extractor_version"],
        "properties": {
            "_id": _S, "aliases": _ARR_S, "host": _S, "lang": {"enum": ["vi", "en"]},
            "kind": _S, "title": _S, "title_src": _S, "published_at": _NGAY,
            "published_at_src": _S, "author": _S, "author_src": _S, "cited_source": _S,
            "section": _S,
            "content": {"bsonType": "object", "required": ["text", "word_count", "block"],
                        "properties": {"text": _S, "html": _S, "word_count": _N,
                                       "block": {"bsonType": "object",
                                                 "required": ["path", "method"],
                                                 "properties": {"path": _S, "score": {"bsonType": ["double", "int"]},
                                                                "method": {"enum": ["selector", "heuristic", "fallback"]}}}}},
            "raw": {"bsonType": "object", "properties": {"sha1": _S, "fetched_at": _S}},
            "extractor_version": _S, "extracted_at": _S,
        }},
    "links": {
        "required": ["_id", "src", "dst", "type", "dst_kind", "count", "src_host", "dst_host"],
        "properties": {"_id": _S, "src": _S, "dst": _S, "type": _TYPE, "text": _S,
                       "dst_kind": _KIND, "count": _N, "src_host": _S, "dst_host": _S}},
    "nav_links": {
        "required": ["_id", "host", "dst", "type", "dst_kind", "n_pages"],
        "properties": {"_id": _S, "host": _S, "dst": _S, "type": _TYPE, "text": _S,
                       "dst_kind": _KIND, "n_pages": _N, "sample_src": _ARR_S}},
    "images": {
        "required": ["_id", "host", "is_template"],
        "properties": {"_id": _S, "host": _S, "alts": _ARR_S, "is_template": _B}},
    "documents": {
        "required": ["_id", "host", "ext", "status"],
        "properties": {"_id": _S, "host": _S, "ext": _S, "mime": _S, "size": _N, "sha1": _S,
                       "status": {"enum": ["pending", "ok", "unsupported", "skipped_too_large", "error"]},
                       "text": _S, "n_pages": _N, "needs_ocr": _B, "encoding_suspect": _B,
                       "error": _S, "fetched_at": _S, "extractor_version": _S}},
    "templates": {
        "required": ["_id", "n_pages", "blocks"],
        "properties": {"_id": _S, "n_pages": _N, "blocks": {"bsonType": "object"}}},
}

INDEXES = {
    "links": [[("dst", 1)], [("src", 1)]],
    "nav_links": [[("dst", 1)]],
    "pages": [[("host", 1), ("published_at", -1)], [("aliases", 1)]],
    "documents": [[("host", 1)]],
}

_client = None


def get_db():
    global _client
    from pymongo import MongoClient
    if _client is None:
        _client = MongoClient(MONGO_URL, serverSelectionTimeoutMS=3000)
    return _client[DB_NAME]


def init(db) -> None:
    """Tạo collection kèm validator và index; chạy lại nhiều lần không sao."""
    have = set(db.list_collection_names())
    for name, schema in SCHEMAS.items():
        validator = {"$jsonSchema": {"bsonType": "object", **schema}}
        if name in have:
            db.command("collMod", name, validator=validator, validationAction="error")
        else:
            db.create_collection(name, validator=validator, validationAction="error")
    for name, idxs in INDEXES.items():
        for keys in idxs:
            db[name].create_index(keys)
