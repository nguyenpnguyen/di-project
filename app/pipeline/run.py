"""CLI: python -m app.pipeline.run <lệnh>
  ontology         nạp ESCO/seed
  ingest [nguồn] [limit] [days]   chạy 1 hoặc tất cả wrapper, có ngân sách
  index            đánh lại index tìm kiếm
  all              chạy trọn pipeline
"""
import sys
from app.pipeline import ontology, ingest, index
from app.wrappers.sources import REGISTRY


def main(argv):
    cmd = argv[0] if argv else "all"
    if cmd in ("ontology", "all"):
        print("ontology:", ontology.load_seed(), "seed |", ontology.load_esco(), "esco")
    if cmd in ("ingest", "all"):
        names = [argv[1]] if len(argv) > 1 and cmd == "ingest" else list(REGISTRY)
        lim = int(argv[2]) if len(argv) > 2 else 100
        days = int(argv[3]) if len(argv) > 3 else None
        for n in names:
            try:
                print(n, ingest.run_source(n, lim, since=days))
            except Exception as e:
                print(n, "FAILED:", e)
    if cmd in ("index", "all"):
        print("indexed:", index.reindex())


if __name__ == "__main__":
    main(sys.argv[1:])
