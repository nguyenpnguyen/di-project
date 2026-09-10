#!/usr/bin/env bash
# Test tích hợp: chạy trên stack docker compose đang bật.
# Kiểm cả đường đi thật — crawl từ file link, index, tìm kiếm, tô sáng.
#
#   docker compose up -d && ./tests/integration.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
API=${API:-http://localhost:8000}
PASS=0; FAIL=0

ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
no()   { printf "  \033[31m✗\033[0m %s\n     %s\n" "$1" "${2:-}"; FAIL=$((FAIL+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else no "$1" "mong $3, nhận $2"; fi; }
has()  { case "$2" in *"$3"*) ok "$1";; *) no "$1" "không thấy '$3' trong: ${2:0:150}";; esac; }

echo "── 1. Dịch vụ sống ──"
h=$(curl -s --max-time 10 "$API/api/health")
has "api trả lời"        "$h" '"api":true'
has "api nối được lucene" "$h" '"lucene":true'

echo "── 2. Index có dữ liệu ──"
st=$(curl -s --max-time 20 "$API/api/index/stats")
docs=$(echo "$st" | sed -n 's/.*"docs":\([0-9]*\).*/\1/p')
if [ "${docs:-0}" -gt 0 ]; then ok "index có $docs tài liệu"; else no "index rỗng" "$st"; fi

echo "── 3. Tìm kiếm ──"
r=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=điểm chuẩn" --data-urlencode "size=3")
total=$(echo "$r" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
if [ "${total:-0}" -gt 0 ]; then ok "tìm 'điểm chuẩn' ra $total kết quả"; else no "không ra kết quả" "$r"; fi
has "có tô sáng bằng thẻ mark" "$r" "<mark>"
has "trả về url gốc để mở tab mới" "$r" 'https://'

echo "── 4. Tìm tiếng Việt có dấu ──"
r2=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=tuyển sinh")
t2=$(echo "$r2" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
if [ "${t2:-0}" -gt 0 ]; then ok "'tuyển sinh' ra $t2 kết quả"; else no "dấu tiếng Việt hỏng" "$r2"; fi

echo "── 5. Truy vấn sai cú pháp trả 400, không phải 500 ──"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 --get "$API/api/search" --data-urlencode "q=điểm AND AND")
check "mã lỗi" "$code" "400"

echo "── 6. Lọc theo host ──"
r3=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=thư viện" --data-urlencode "host=library.hust.edu.vn")
if echo "$r3" | grep -q '"host":"library.hust.edu.vn"' || [ "$(echo "$r3" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')" = "0" ]; then
  ok "lọc host không lẫn site khác"
else no "lọc host sai" "${r3:0:200}"; fi

echo "── 7. Thống kê kho ──"
s=$(curl -s --max-time 20 "$API/api/stats")
has "có danh sách site" "$s" '"sites"'
has "có đếm link"       "$s" '"links"'

echo "── 8. Điều khiển crawl ──"
c=$(curl -s --max-time 10 "$API/api/crawl/status")
has "báo được trạng thái crawl" "$c" '"running"'
stop=$(curl -s --max-time 40 -X POST "$API/api/crawl/stop")
has "gọi dừng không lỗi" "$stop" '"ok":true'

echo "── 9. Bỏ dấu, gộp trùng, lọc ngày ──"
kd=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=diem chuan" --data-urlencode "size=3")
n_kd=$(echo "$kd" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
if [ "${n_kd:-0}" -gt 0 ]; then ok "gõ không dấu vẫn ra kết quả ($n_kd)"; else no "gõ không dấu vẫn ra kết quả" "total=0"; fi
has "đoạn trích tô cả chữ có dấu" "$kd" "<mark>"
has "kết quả có trường bản trùng" "$kd" '"duplicates"'

sd=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=tuyển sinh" \
     --data-urlencode "sort=date" --data-urlencode "size=5")
has "xếp theo ngày được ghi nhận" "$sd" '"sort":"date"'
d_new=$(echo "$sd" | sed -n 's/.*"date":"\([0-9-]*\)".*/\1/p' | head -1)
if [ -n "$d_new" ]; then ok "kết quả đầu có ngày ($d_new)"; else no "kết quả đầu có ngày" "trống"; fi

lo=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=tuyển sinh" \
     --data-urlencode "date_from=2026-01-01" --data-urlencode "size=5")
if echo "$lo" | grep -qE '"date":"20(1[0-9]|2[0-5])-'; then
  no "lọc ngày không lọt bài cũ" "$(echo "$lo" | grep -oE '"date":"20[0-9-]*"' | head -3)"
else ok "lọc ngày không lọt bài cũ"; fi

echo "── 10. Xem trước và tải lẻ ──"
u1=$(echo "$kd" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | head -1)
pv=$(curl -s --max-time 20 --get "$API/api/preview" --data-urlencode "url=$u1")
has "xem trước trả HTML đã dọn" "$pv" '"html"'
if echo "$pv" | grep -qiE '<script|onerror=|javascript:'; then
  no "xem trước đã bỏ script" "còn thẻ chạy được"; else ok "xem trước đã bỏ script"; fi
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 --get "$API/api/preview" --data-urlencode "url=https://khong-co-that.example/x")
check "url lạ trả 404 chứ không 500" "$code" "404"

echo "── 11. Giao diện ──"
ui=$(curl -s --max-time 10 "$API/")
has "trang chủ trả HTML"    "$ui" "<title>"
has "có font tiếng Việt"    "$ui" "Be+Vietnam+Pro"
has "có bảng điều khiển"    "$ui" "Bảng điều khiển"

echo ""
echo "═══ $PASS đạt, $FAIL hỏng ═══"
[ "$FAIL" -eq 0 ]
