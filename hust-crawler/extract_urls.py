#!/usr/bin/env python3
"""Append unique URLs from data/raw/state.json into urls.txt.

Chạy lại bao nhiêu lần cũng được: URL đã có trong file đích sẽ bị bỏ qua.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main() -> None:
    parser = argparse.ArgumentParser(description="Trích URL duy nhất từ state.json")
    parser.add_argument("--raw-dir", type=Path, default=ROOT / "data" / "raw",
                        help="thư mục chứa state.json")
    parser.add_argument("--output", type=Path, default=ROOT / "urls.txt",
                        help="file txt đầu ra, mỗi URL một dòng")
    args = parser.parse_args()

    raw_dir = args.raw_dir.resolve()
    output = args.output.resolve()
    state_path = raw_dir / "state.json"
    if not state_path.is_file():
        parser.error(f"Không thấy state.json trong {raw_dir}")

    state = json.loads(state_path.read_text(encoding="utf-8"))
    urls = state.get("queued", [])
    if not isinstance(urls, list):
        parser.error("state.json không có danh sách queued hợp lệ")

    old_text = output.read_text(encoding="utf-8") if output.exists() else ""
    seen = {line.strip() for line in old_text.splitlines() if line.strip()}
    added: list[str] = []

    for url in urls:
        if not isinstance(url, str):
            continue
        url = url.strip()
        if url and url not in seen:
            seen.add(url)
            added.append(url)

    output.parent.mkdir(parents=True, exist_ok=True)
    if added:
        with output.open("a", encoding="utf-8") as file:
            if old_text and not old_text.endswith(("\n", "\r")):
                file.write("\n")
            file.write("\n".join(added) + "\n")
    elif not output.exists():
        output.touch()

    print(f"Đã thêm: {len(added)} URL")
    print(f"Tổng cộng: {len(seen)} URL trong {output}")


if __name__ == "__main__":
    main()
