# Build và chạy tách hai tầng: image cuối chỉ có JRE + jar, không kéo theo maven.
# Giữ nguyên jar gốc của Lucene/Tika trong lib/ thay vì gói phẳng — xem ghi chú trong pom.xml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/hust-search.jar ./hust-search.jar
COPY --from=build /src/target/lib ./lib
COPY static ./static
ENV INDEX_DIR=/index DATA_DIR=/data STATIC_DIR=/app/static PORT=8000
EXPOSE 8000
# Xmx 1g: Lucene dựa vào page cache của hệ điều hành, phần heap còn lại cho jsoup/Tika/driver Mongo
CMD ["java","-Xmx1g","-jar","hust-search.jar"]
