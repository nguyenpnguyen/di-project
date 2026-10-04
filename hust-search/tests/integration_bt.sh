#!/usr/bin/env bash
# Test tích hợp phần bóc tách + đồ thị + Mongo, chạy trên stack đang bật:
#   (cd .. && docker compose up -d --build) && ./tests/integration_bt.sh
# Cần kho hust-crawler/data có ít nhất vài chục trang. Bước bóc tách chạy nền nên
# script chờ tối đa WAIT giây (mặc định 900) — kho vài nghìn trang có thể lâu hơn.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
API=${API:-http://localhost:8000}
WAIT=${WAIT:-900}
PASS=0; FAIL=0

ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
no()   { printf "  \033[31m✗\033[0m %s\n     %s\n" "$1" "${2:-}"; FAIL=$((FAIL+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else no "$1" "mong $3, nhận $2"; fi; }
has()  { case "$2" in *"$3"*) ok "$1";; *) no "$1" "không thấy '$3' trong: ${2:0:200}";; esac; }
cho_job() {   # chờ job nền xong, in kết quả
  local t=0 s
  while [ $t -lt "$WAIT" ]; do
    s=$(curl -s --max-time 10 "$API/api/extract/status")
    case "$s" in *'"running":false'*) echo "$s"; return 0;; esac
    sleep 3; t=$((t+3))
  done
  echo "$s"; return 1
}

echo "── 1. Mongo sống ──"
h=$(curl -s --max-time 20 "$API/api/extract/coverage")
case "$h" in *'"detail"'*) no "api nối được Mongo" "$h"; exit 1;; *) ok "api nối được Mongo";; esac

echo "── 2. Bảng khuôn và bóc tách ──"
curl -s --max-time 10 -X POST "$API/api/extract/templates" >/dev/null
r=$(cho_job) && has "dựng bảng khuôn xong" "$r" '"error":null' || no "dựng bảng khuôn" "$r"
curl -s --max-time 10 -X POST "$API/api/extract/run" >/dev/null
r=$(cho_job) && has "bóc tách xong" "$r" '"error":null' || no "bóc tách" "$r"
n1=$(echo "$r" | sed -n 's/.*"pages":\([0-9]*\).*/\1/p')
if [ "${n1:-0}" -gt 0 ]; then ok "bóc được $n1 trang"; else no "không bóc được trang nào" "$r"; fi

echo "── 3. Chạy lại không nhân đôi ──"
s1=$(curl -s --max-time 20 "$API/api/graph/stats")
curl -s --max-time 10 -X POST "$API/api/extract/run" >/dev/null; cho_job >/dev/null
s2=$(curl -s --max-time 20 "$API/api/graph/stats")
check "số trang, cạnh, ảnh không đổi sau lần chạy thứ hai" "$(echo "$s2" | sed 's/"top_in_degree".*//')" "$(echo "$s1" | sed 's/"top_in_degree".*//')"

echo "── 4. Độ phủ trường ──"
cov=$(curl -s --max-time 20 "$API/api/extract/coverage")
has "có tỉ lệ tiêu đề" "$cov" '"title_pct"'
has "có cách chọn khối" "$cov" '"methods"'

echo "── 5. Nguồn giới thiệu ──"
top=$(echo "$s2" | sed -n 's/.*"top_in_degree":\[{"url":"\([^"]*\)".*/\1/p')
if [ -n "$top" ]; then
  ref=$(curl -s --max-time 20 --get "$API/api/referrers" --data-urlencode "url=$top")
  has "referrers trả trang nguồn" "$ref" '"src"'
  has "referrers trả chữ mô tả" "$ref" '"text"'
else no "đồ thị không có cạnh nội dung nào" "$s2"; fi
code=$(curl -s -o /dev/null -w '%{http_code}' --get "$API/api/referrers" --data-urlencode "url=ftp://x")
check "url không phải http trả 422" "$code" "422"
csv=$(curl -s --max-time 60 "$API/api/graph/edges.csv" | head -2)
has "edges.csv có tiêu đề cột" "$csv" "source,target,text"

echo "── 6. Index từ Mongo, lọc kind ──"
r=$(curl -s --max-time 600 -X POST "$API/api/index/run" -H 'content-type: application/json' -d '{"reset":true,"source":"mongo"}')
has "index từ Mongo" "$r" '"source":"mongo"'
p=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=tuyển sinh" --data-urlencode "kind=page" --data-urlencode "size=3")
has "kết quả có trường author" "$p" '"author"'
has "kết quả có trường kind" "$p" '"kind":"page"'
d=$(curl -s --max-time 30 --get "$API/api/search" --data-urlencode "q=tuyển sinh" --data-urlencode "kind=document" --data-urlencode "size=3")
nd=$(echo "$d" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
if [ "${nd:-0}" -gt 0 ]; then has "kind=document ra tệp" "$d" '"kind":"document"'
else echo "  (chưa có tệp nào được index — chạy /api/files/fetch và /api/files/extract trước để kiểm bước này)"; fi
case "$d" in *'"kind":"page"'*) no "kind=document lọt trang" "${d:0:200}";; *) ok "kind=document không lọt trang";; esac

echo "── 7. Tệp và ảnh ──"
f=$(curl -s --max-time 20 "$API/api/files")
has "danh sách tệp trả JSON" "$f" '"by_status"'
i=$(curl -s --max-time 20 "$API/api/images?limit=3")
has "danh sách ảnh trả JSON" "$i" '"items"'

echo "── 8. Lược đồ Mongo từ chối bản ghi sai (cần docker compose) ──"
COMPOSE="docker compose -f ../docker-compose.yml"     # compose nằm ở thư mục cha
if command -v docker >/dev/null 2>&1 && $COMPOSE ps mongo >/dev/null 2>&1; then
  v=$($COMPOSE exec -T mongo mongosh --quiet --eval \
      'try{db.getSiblingDB("hust").pages.insertOne({_id:"x"});print("ACCEPTED")}catch(e){print("REJECTED")}' 2>&1 | tail -1)
  check "validator từ chối trang thiếu trường bắt buộc" "$v" "REJECTED"
else echo "  (bỏ qua: không có docker compose)"; fi

echo ""
echo "═══ $PASS đạt, $FAIL hỏng ═══"
[ "$FAIL" -eq 0 ]
