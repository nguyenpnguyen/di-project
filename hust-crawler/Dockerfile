# Crawler chạy riêng: chỉ requests/bs4/lxml, KHÔNG Playwright (nên không crawl được trang dựng bằng JS,
# vd. work.hust.edu.vn). Code mount vào /app khi chạy (compose) nên sửa crawl_all.py không phải build lại.
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . /app
ENV PYTHONUNBUFFERED=1
EXPOSE 8090
CMD ["python", "crawlctl.py", "--port", "8090"]
