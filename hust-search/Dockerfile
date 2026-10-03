# Build và chạy tách hai tầng: image cuối chỉ có JRE + jar, không kéo theo maven.
# Giữ nguyên jar gốc của Lucene trong lib/ thay vì gói phẳng — xem ghi chú trong pom.xml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/hust-search.jar ./hust-search.jar
COPY --from=build /src/target/lib ./lib
ENV INDEX_DIR=/index PORT=8081
EXPOSE 8081
# Xmx vừa phải: Lucene dựa vào page cache của hệ điều hành nhiều hơn là heap
CMD ["java","-Xmx512m","-jar","hust-search.jar"]
