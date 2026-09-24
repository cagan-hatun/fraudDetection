# Fraud Detection Platform

[![CI](https://github.com/cagan-hatun/fraudDetection/actions/workflows/ci.yml/badge.svg)](https://github.com/cagan-hatun/fraudDetection/actions/workflows/ci.yml)

Bankacılık işlemlerinde dolandırıcılık (fraud) tespiti için uçtan uca bir sistem: veri biliminden (LightGBM + SHAP) gerçek zamanlı bir mikroservis mimarisine (FastAPI + Spring Boot + Kafka), oradan da bir analistin karar verdiği bir arayüze (React) kadar. **Portfolyö/öğrenme amaçlı** hazırlandı, canlıya alınmayacak — ama kısayol değil, gerçek mühendislik titizliği hedeflendi: her mimari karar gerekçesiyle belgelendi, her dayanıklılık deseni (retry/DLQ, circuit breaker, idempotency) gerçek bir arızayla tetiklenip canlı doğrulandı.

## Neden bu proje

Çoğu "fraud detection" portfolyö projesi bir notebook'ta biten bir model eğitimidir. Bu proje modelin ÇALIŞTIĞI yeri de gösteriyor: bir işlem geldiğinde ne olur, model ve kural motoru aynı fikirde değilse ne olur, bir servis çökerse mesaj kaybolur mu, bir analist modele neden güvenmediğini nasıl SHAP ile görür, bir yönetici o kararı nasıl onaylar/reddeder. Ayrıca her README'de **neyin bilinçli olarak yapılmadığı** da açıkça yazıyor — "her şeyi yaptım" değil, "neyi neden yapmadığımı biliyorum" yaklaşımı tercih edildi.

## Mimari

```
                              ┌─────────────────────┐
                              │   React Frontend     │
                              │ (giriş, dashboard,   │
                              │  SHAP grafiği, review)│
                              └──────────┬───────────┘
                                         │ REST + JWT
                                         ▼
                              ┌──────────────────────┐
                              │   Spring Boot Backend │
                              │  (Transaction API,    │
                              │   Rule Engine, JWT)    │
                              └──────────┬───────────┘
                                         │ PostgreSQL (yazma)
                                         │ Kafka (asenkron event)
                        ┌────────────────┴────────────────┐
                        ▼                                  ▼
              ┌──────────────────┐              ┌──────────────────────┐
              │  ML Service        │              │  Rule Engine          │
              │  (FastAPI/LightGBM)│              │  (SpEL, rules.yaml)   │
              │  → fraud_probability│              │  → matched_rules      │
              └─────────┬──────────┘              └──────────┬───────────┘
                        └───────────────┬──────────────────────┘
                                        ▼
                        Escalate-only merge (final_action)
                        BLOCK > REVIEW > APPROVE — Rule Engine
                        bir kararı SADECE sertleştirebilir
```

İki karar kaynağı BAĞIMSIZ çalışır (ayrı Kafka consumer group'ları, aynı mesajın kendi kopyasını alırlar) ve sonuç "escalate-only" mantığıyla birleştirilir — Rule Engine'in ML kararını yumuşatması hiçbir zaman mümkün değildir, sadece sertleştirebilir. Detaylı akış diagramı ve her adımın gerekçesi: [`backend/README.md`](backend/README.md).

## Öne çıkan özellikler

- **Uçtan uca ML:** Leakage-safe feature engineering, maliyet-duyarlı threshold optimizasyonu, SHAP açıklanabilirlik — bkz. [Sonuçlar](#sonuçlar).
- **Gerçek asenkron mimari:** Kafka üzerinden iki bağımsız consumer (ML + Rule Engine), idempotent finalize deseni, "dual write" tuzağından `afterCommit()` ile kaçınma.
- **Dayanıklılık, gerçekten test edilmiş:** Retry+DLQ (Testcontainers ile doğrulandı), Resilience4j circuit breaker (state-transition testleriyle), ve **DLQ redrive aracı** — ml-service'i gerçekten geçici olarak erişilemez yaparak canlı tetiklenmiş, sonra düzeltilip yeniden işletilmiş bir arıza senaryosuyla doğrulandı.
- **Gerçek transaction ingestion API'si:** Sabit demo senaryolarına bağımlı olmayan, çağıranın kendi feature vektörünü sağladığı bir uç nokta — "neden sadece gerçekçi alanlarla değil de tam feature vektörüyle" sorusunun cevabı [`backend/README.md`](backend/README.md)'de (bir PR-AUC deneyine dayanıyor).
- **Operasyonel olgunluk:** Spring Boot Actuator health check'leri (Docker healthcheck zincirinin dayandığı gerçek uç nokta), login rate limiting (bucket4j), idempotency key desteği, rol bazlı erişim kontrolü (ADMIN-only DLQ redrive).
- **Analist arayüzü:** SHAP grafiği, ML kararı + Rule Engine kararı + nihai kararın ayrı ayrı gösterimi, REVIEW durumundaki işlemler için onay/red akışı.
- **CI/CD:** Her push'ta backend (unit + Testcontainers entegrasyon testleri) ve frontend (test + build) otomatik çalışır.
- **Docker Compose, iki mod:** Sadece altyapı (hot-reload'lı geliştirme) veya tek komutla tam stack (gerçek healthcheck zinciriyle).

## Sonuçlar

| Metrik | Değer |
|---|---|
| PR-AUC (holdout) | 0.5500 |
| Recall | %83.5 |
| Maliyet tasarrufu (naif eşiğe göre) | %62.8 |
| Feature seti | Top-100 (9 türetilmiş + 11 yorumlanabilir + ~100 anonim IEEE-CIS sütunu) |
| Model | LightGBM (tuned), MLflow registry'de |

Metodoloji, walk-forward validation, threshold optimizasyonu ve SHAP analizi: [`ml/README.md`](ml/README.md).

## Teknoloji

| Katman | Teknoloji |
|---|---|
| ML | Python, LightGBM, SHAP, MLflow, pandas |
| ML Servisi | FastAPI, Pydantic (dinamik şema üretimi) |
| Backend | Spring Boot 4.1.1, Spring Security (JWT), Kafka, PostgreSQL, Flyway, Resilience4j, bucket4j, springdoc-openapi |
| Frontend | React 19, TypeScript, MUI v9, Recharts, TanStack Query, React Router |
| Altyapı | Docker Compose (iki mod), GitHub Actions CI, Testcontainers |
| Veri seti | IEEE-CIS Fraud Detection (Kaggle) |

## Repo yapısı (monorepo)

```
fraudDetection/
├── ml/         # Veri analizi, feature engineering, model eğitimi
├── ml-service/ # FastAPI ML servisi (/predict, /explain)
├── backend/    # Spring Boot: Transaction API, Rule Engine, Kafka, JWT, DLQ redrive
├── frontend/   # React + TypeScript dashboard
└── infra/      # Docker Compose (iki mod) ve CI
```

Her klasörün kendi `README.md`'si var — mimari kararlar, "neden böyle" gerekçeleri, çalıştırma talimatları, test sayıları ve dürüst sınırlılıklar orada:

- [`ml/README.md`](ml/README.md) — feature engineering, model seçimi, threshold optimizasyonu, SHAP
- [`ml-service/README.md`](ml-service/README.md) — FastAPI servisi, dinamik şema üretimi
- [`backend/README.md`](backend/README.md) — Kafka pipeline'ı, escalate-only merge, dayanıklılık desenleri, ingestion API, DLQ redrive
- [`frontend/README.md`](frontend/README.md) — API client üretimi, polling deseni, tasarım kararları
- [`infra/README.md`](infra/README.md) — Docker Compose'un iki modu, Kafka dual-listener kurulumu

## Hızlı başlangıç

```bash
# Tam stack, tek komutla (backend/ml-service/frontend'in her biri kendi Dockerfile'ında)
docker compose -f infra/docker-compose.yml up -d --build
```

`http://localhost:5173` — demo giriş: `analyst` / `ChangeMe123!`. Günlük geliştirme (hot-reload) için ayrı mod ve tüm adımlar: [`infra/README.md`](infra/README.md).

## Test durumu

| Bileşen | Test sayısı |
|---|---|
| Backend | 69 (67 unit + 2 Testcontainers entegrasyon) |
| Frontend | 21 |
| ML Servisi | 15 |

Her push'ta backend + frontend testleri otomatik çalışır (yukarıdaki CI badge'i).

## Dürüst sınırlılıklar

Bilinçli olarak yapılmamış/dışarıda bırakılmış şeyler (neden'leri ilgili README'lerde):

- Frontend'de `DashboardPage`/`TransactionDetailPage` için sayfa testi yok — kritik iş mantığı taşıyan `LoginPage`/`ReviewPanel` önceliklendirildi.
- Uçtan uca (Playwright/Cypress) bir tarayıcı testi yok.
- Model drift/performans izleme yok — model tek seferlik eğitilip kaydedildi, canlıda izlenmiyor.
- Gerçek bir feature store yok — IEEE-CIS'in ~100 anonim sütunu nasıl hesaplandığı bilinmediği için sıfırdan yeniden üretilemiyor; ingestion API bu sınırı [`backend/README.md`](backend/README.md)'de açıkça belgeliyor.
