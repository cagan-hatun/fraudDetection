# Fraud Detection — Spring Boot Backend

Bankacılık senaryosunu simüle eden REST API: işlemleri (demo fixture'lar üzerinden) "replay" eder, `ml-service`'i (FastAPI/LightGBM) senkron çağırır, sonucu ve SHAP açıklamalarını PostgreSQL'e yazar, JWT ile korunur. Mimarideki rolü: `Transaction → PostgreSQL → (ml-service + Rule Engine) → Risk Kararı` akışının Spring tarafı — Kafka/Rule Engine parçaları henüz eklenmedi (bkz. Sıradaki Adımlar).

> Bu servis `ml-service`'e REST üzerinden bağımlı. Çalıştırmadan önce `ml-service/README.md`'deki adımlarla onu ayağa kaldır.

## Proje Yapısı

```
backend/src/main/java/com/fraud/project/
├── entity/          # JPA entity'leri (User, Device, Merchant, Transaction, RiskScore,
│                    #   Explanation, AuditLog, AppUser + RiskAction/AppRole enum'ları)
├── repository/      # Spring Data JPA repository'leri
├── fixture/         # Demo "replay" fixture'ları (DemoTransactionFixture, DemoFixtureLoader)
├── mlservice/       # ml-service REST istemcisi (MlServiceClient, PredictionResult,
│                    #   ExplanationResult, FeatureContribution)
├── service/         # İş mantığı (TransactionReplayService, ReplayResult)
├── security/        # JWT (JwtService, JwtAuthenticationFilter, AppUserDetailsService)
├── controller/      # REST endpoint'leri (DemoController, AuthController + DTO'lar)
└── config/          # SecurityConfig, MlServiceConfig

backend/src/main/resources/
├── db/migration/    # Flyway migration'ları (V1: domain şeması, V2: app_users)
└── fixtures/        # demo_transactions.json — IEEE-CIS holdout'tan gerçek satırlar
```

## Mimari Kararlar ve Kavramlar

### 1. Hibrit şema: gerçekçi domain + JSONB feature snapshot

ML modeli IEEE-CIS'in 120 ham/anonim sütunuyla (`V1-339`, `C1-14`, `D1-15`... çoğunun gerçek dünya karşılığı yok) çalışıyor, ama gerçekçi bir bankacılık şeması `users`/`devices`/`merchants`/`transactions` gibi anlamlı alanlar bekler. Çözüm: domain tabloları normalize ve okunaklı kalıyor; `risk_scores.feature_snapshot` (JSONB) modele gönderilen TAM ham vektörü saklıyor. Bu snapshot aynı zamanda `explanations` tablosunun kaynağı — "hangi tam feature vektörüyle bu karar verildi" sorusu denetim için net cevaplanabiliyor.

### 2. Demo veriler DB'ye seed edilmiyor, JSON fixture olarak duruyor

`fixtures/demo_transactions.json`, IEEE-CIS holdout setinden (modele hiç karışmamış, gerçek etiketli) seçilmiş 6 gerçek satır içeriyor: `caught_fraud` (doğru yakalanan), `missed_fraud` (kaçırılan — modelin kör noktası), `false_positive` (yanlış alarm), `ordinary_small/medium/large`. Bilinçli bir tasarım kararı: veritabanı pipeline'ın ÇIKTISI olmalı, GİRDİSİ değil — fixture'lar DB'ye önceden yazılsaydı `replay` endpoint'inin "sıfırdan gerçekten çalıştığını" göstermenin bir anlamı kalmazdı. Anonim sütunlar (V/C/D...) sentetik ÜRETİLMEDİ — istatistiksel örnekleme modelin asıl sinyalini gürültüye çevirirdi, bu yüzden gerçek holdout satırları kullanıldı.

### 3. `TransactionReplayService` — tek bir `@Transactional` akış

Bir fixture seçildiğinde: User/Device/Merchant find-or-create → Transaction kaydet → `ml-service /predict` çağır → RiskScore kaydet → `ml-service /explain` çağır → her feature katkısı için bir Explanation satırı → bir AuditLog satırı (`actor=SYSTEM`, çünkü şu an bu akışı tetikleyen bir insan analist yok). Hepsi tek transaction sınırında — `/predict` ya da `/explain` başarısız olursa tüm yazımlar geri alınır.

### 4. ml-service entegrasyonu — Spring Boot 4.1.1'in yeni/parçalanmış yapısıyla boğuşma

Bu, en çok zaman alan kısımdı; üç ayrı, birbirinden bağımsız hata çıktı:
- `spring-boot-starter-webmvc`, eski `spring-boot-starter-web`'in aksine Jackson'ı otomatik getirmiyor → `spring-boot-starter-json` eklendi.
- Boot 4.1.1 varsayılan olarak **Jackson 3**'e geçmiş (`tools.jackson.*`, eski `com.fasterxml.jackson.databind` değil — `@JsonProperty` gibi annotation'lar aynı kaldı, sadece core/databind taşındı). java.time desteği artık databind'e gömülü.
- `RestClient.Builder` bu sürümde autoconfigure edilmiyor; çıplak `RestClient.builder()` bir `Map` body'sini JSON'a serileştiremiyordu (hatasız, sessizce boş body). Jackson 3 tabanlı `JacksonJsonHttpMessageConverter` elle eklendi.
- JDK `HttpClient`'ın varsayılan davranışı uvicorn'un desteklemediği bir HTTP/2 upgrade denemesi yapıyordu → `HttpClient.Version.HTTP_1_1`'e sabitlendi.

Tüm çözümler `config/MlServiceConfig.java`'da.

### 5. JWT — elle yazılmış filtre, ayrı `app_users` tablosu

İki bilinçli tercih: (a) JWT, Spring'in OAuth2 Resource Server soyutlaması yerine `io.jsonwebtoken` (jjwt) ile elle yazıldı — token üretimi/doğrulamasının gerçekten nasıl çalıştığını göstermek için (mülakat/öğrenme değeri). (b) Giriş yapan analist/admin hesapları (`app_users`), skorlanan banka müşterisini temsil eden `users` tablosundan tamamen ayrı — bunlar birbirine karıştırılabilecek ama kavramsal olarak apayrı iki kavram. `JwtAuthenticationFilter` token'ı doğrulayıp `SecurityContext`'i dolduruyor; asıl "yetkili mi" kararını `SecurityConfig`'deki `authorizeHttpRequests` veriyor.

### 6. Test stratejisi

Şu an tamamı **unit test** (Mockito ile DB/ml-service mock'lanıyor) — gerçek veritabanı gerektiren entegrasyon testleri bilinçli olarak ertelendi, Testcontainers eklenene kadar (bkz. Sıradaki Adımlar). Bunun yerine her önemli akış, gerçek Postgres + gerçek ml-service'e karşı elle (curl ile) uçtan uca doğrulandı.

## Veritabanı Şeması

| Tablo | Amaç |
|---|---|
| `users`, `devices`, `merchants`, `transactions` | Gerçekçi bankacılık domain'i |
| `risk_scores` | ML kararı: `fraud_probability`, `action`, `model_version`, `feature_snapshot` (JSONB) |
| `explanations` | Bir risk_score'a bağlı SHAP katkıları (uzun/long format — feature sayısı şemayı etkilemez) |
| `audit_log` | Denetim izi: kim/ne zaman/hangi model/hangi eşikle karar verildi (BDDK gereksinimi) |
| `app_users` | JWT ile giriş yapan analist/admin hesapları (banking müşterisiyle KARIŞTIRILMAMALI) |

## API Uç Noktaları

| Method & Path | Auth | Açıklama |
|---|---|---|
| `POST /api/auth/login` | Açık | `{username, password}` → `{token}` |
| `GET /api/demo/scenarios` | Bearer JWT | Fixture senaryolarının listesi |
| `POST /api/demo/replay/{scenarioId}` | Bearer JWT | Bir senaryoyu uçtan uca çalıştırır |

## Çalıştırma

```bash
# 1. Postgres (dev)
docker compose -f ../infra/docker-compose.yml up -d

# 2. ml-service (ayrı terminalde, bkz. ml-service/README.md)
cd ../ml-service && uvicorn app.main:app --reload

# 3. Backend
cd backend
./mvnw spring-boot:run
```

```bash
# Giriş yap (demo hesap: analyst / ChangeMe123!)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"analyst","password":"ChangeMe123!"}' | jq -r .token)

# Bir senaryoyu çalıştır
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/demo/replay/caught_fraud
```

## Test

```bash
./mvnw test
```

16 test (Docker/DB gerektirmez): `DemoFixtureLoaderTest` (fixture JSON parse + gerçek dosya doğrulaması), `TransactionReplayServiceTest` (find-or-create mantığı, ml-service çağrıları, feature_snapshot/explanations/audit_log yazımı), `JwtServiceTest` (token üretme/doğrulama, süre dolması, imza bozulması), `AppUserDetailsServiceTest`.

## Bilinen Sınırlılıklar / Sıradaki Adımlar

- **Kafka asenkron akışı henüz yok** — şu an her şey senkron (`DemoController` → `TransactionReplayService` → `ml-service`). Planlanan akış: `Transaction API → PostgreSQL → Kafka → ML Service + Rule Engine` paralel tüketir.
- **Rule Engine yok** — configurable YAML/JSON kural motoru henüz eklenmedi.
- **Gerçek transaction ingestion API'si yok** — şu an sadece önceden tanımlı 6 demo senaryosu "replay" edilebiliyor, keyfi bir işlem submit edilemiyor (bilinçli — bkz. yukarıdaki fixture kararı, IEEE-CIS'in anonim sütunları serbest girişle doldurulamaz).
- **Testcontainers henüz yok** — testler Mockito ile izole; gerçek DB'ye karşı entegrasyon testleri ayrı bir iş kalemi.
- **Resilience4j (circuit breaker/retry) yok** — ml-service kesintisi senaryosu henüz ele alınmadı.
- **Global exception handler yok** — hata cevapları şu an Spring'in varsayılan formatında (stack trace dahil), production'a uygun değil.
