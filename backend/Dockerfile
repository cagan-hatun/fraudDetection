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

# Sadece docker-compose healthcheck'inin /actuator/health'i çağırabilmesi için
# — imajın geri kalanı hâlâ minimal (JRE + jar).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
