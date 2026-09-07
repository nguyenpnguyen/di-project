# Image Playwright có sẵn Chromium và đủ thư viện hệ thống — tự cài trên
# python:slim thì phải kéo vài chục gói apt và rất hay thiếu cái này cái kia.
FROM mcr.microsoft.com/playwright/python:v1.47.0-jammy
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . /app
ENV CRAWLER_DIR=/crawler DATA_DIR=/crawler/data LUCENE_URL=http://lucene:8081 \
    PYTHONPATH=/crawler PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["uvicorn","main:app","--host","0.0.0.0","--port","8000"]
