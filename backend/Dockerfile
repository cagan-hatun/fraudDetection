# Build stage — pom.xml + .mvn önce kopyalanıyor ki src/ değişse bile bağımlılık
# katmanı (Docker layer cache) yeniden indirilmesin.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

COPY src src
# Testler CI/lokal geliştirmede zaten çalıştırılıyor (bkz. README) — image
# build'inde tekrar koşmuyoruz, ayrıca Testcontainers testleri Docker-in-Docker
# gerektirir ki bu build ortamında mevcut değil.
RUN ./mvnw package -DskipTests -B

# Runtime stage — sadece JRE + üretilen jar, build araçları imajda kalmıyor.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
