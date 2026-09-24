# Infra — Docker Compose

İki farklı çalıştırma modu destekleniyor; ikisi de aynı `docker-compose.yml`'ı kullanıyor.

## Mod 1 — Sadece altyapı (geliştirme sırasında varsayılan)

Postgres + Kafka container'da, backend/ml-service/frontend host'ta (`mvnw spring-boot:run`, `uvicorn --reload`, `npm run dev`) — hızlı iterasyon için (hot reload). Her servisin kendi README'sindeki "Çalıştırma" bölümü bu modu anlatıyor.

```bash
docker compose up -d postgres kafka
```

## Mod 2 — Tam stack (tek komutla, portfolyöyü incelemek için)

Beşi de container'da: `docker compose up -d --build` tüm sistemi ayağa kaldırır — backend'i Java kurmadan, ml-service'i Python ortamı kurmadan, frontend'i `npm install` yapmadan görmek isteyen biri için.

```bash
# Ön koşul: ml-service/models/ içinde eğitilmiş model dosyaları olmalı
# (bkz. ml-service/README.md — ml/models/'dan kopyalanır, git'e girmez).
docker compose up -d --build
```

`http://localhost:5173` (demo: `analyst` / `ChangeMe123!`), backend `http://localhost:8080`, ml-service `http://localhost:8000/docs`.

**Not — iki modu karıştırmayın:** Host'ta `mvnw spring-boot:run`/`npm run dev` çalışırken aynı anda `docker compose up`'ı backend/frontend servisleriyle de çalıştırırsanız 8080/5173 portları çakışır (`ports are not available` hatası) — önce host süreçlerini durdurun.

### Neden iki mod da var, tek moda indirgenmedi

Host modu geliştirme sırasında (hot reload, debugger attach) günlük kullanım; tam container modu SADECE "tek komutla göster" senaryosu için — ikisi farklı amaçlara hizmet ediyor, biri diğerinin yerini tutmuyor.

### Kafka'nın iki listener'ı neden var

`kafka` servisi HEM `localhost:9092`'yi (host modundaki backend için) HEM `kafka:29092`'yi (container modundaki backend için, container ağı üzerinden) aynı anda dinliyor — "advertised listener" dışarıdan çözülebilir bir adres olmalı ve host ile container'lar "localhost"u farklı şeylere çözer, bu yüzden tek bir listener ikisine birden hizmet edemezdi.

### Backend/ml-service/frontend'in ortam değişkenleri

`docker-compose.yml`'daki `backend` servisinin `environment` bloğu, `application.properties`'teki host-modu varsayılanlarını (localhost adresleri) container ağı adresleriyle override ediyor — Spring'in relaxed binding'i (`ml-service.base-url` → `ML_SERVICE_BASE_URL` gibi) sayesinde kod hiç değişmiyor. `frontend`'in `VITE_API_BASE_URL`'i ise yine `localhost:8080` — çünkü tarayıcıda çalışan kod backend'e container ağından değil, kullanıcının kendi makinesinden erişiyor.
