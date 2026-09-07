#!/bin/bash
# Quét lần lượt từng subdomain để lấy link. Chạy TUẦN TỰ, không song song:
# các site nhiều khả năng dùng chung hạ tầng nên chung luôn rate-limit.
#
#   ./crawl_subdomains.sh                    # danh sách mặc định
#   ./crawl_subdomains.sh soict sem see      # chỉ vài site
#   CAP=100 ./crawl_subdomains.sh            # giới hạn trang mỗi site
cd "$(dirname "$0")" || exit 1
PY=.venv/bin/python
CAP=${CAP:-250}
SITES=${*:-"bulletin tuyendung work research svbk library ts ctsv qldt jst dlib"}

for h in $SITES; do
  host="$h.hust.edu.vn"
  # site đã có kho thì đi tiếp, chưa có thì gieo hạt mới
  [ -f "data/raw-$host/state.json" ] && R="--resume" || R=""
  echo ""
  echo "════ $host (trần $CAP trang) ════"
  $PY crawl_all.py --site "$host" --only listing --max-pages "$CAP" \
      --delay 2.5 --workers 2 $R 2>&1 | grep -E "^\s+\[|Xong|không có sitemap|url từ sitemap|sitemap phẳng|! " | tail -6
done

echo ""
echo "════ GỘP VÀO DANH SÁCH LINK ════"
$PY read_raw.py --links
