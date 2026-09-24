# Fraud Detection — Spring Boot Backend

Bankacılık senaryosunu simüle eden REST API: işlemleri (demo fixture'lar üzerinden) "replay" eder, Kafka üzerinden asenkron olarak hem `ml-service`'i (FastAPI/LightGBM) hem de kendi Rule Engine'ini PARALEL çalıştırır, ikisinin kararını birleştirip sonucu (SHAP açıklamalarıyla birlikte) PostgreSQL'e yazar, JWT ile korunur. Mimarideki rolü: `Transaction → PostgreSQL → Kafka → (ML Service + Rule Engine) → Risk Engine → APPROVE/REVIEW/BLOCK` akışının TAMAMI artık burada.

> Bu servis `ml-service`'e REST, Kafka'ya ise (dev broker) bağımlı. Çalıştırmadan önce `ml-service/README.md`'deki adımlarla onu ayağa kaldır ve `infra/docker-compose.yml`'ı başlat.

## Proje Yapısı

```
backend/src/main/java/com/fraud/project/
├── entity/          # JPA entity'leri (User, Device, Merchant, Transaction, RiskScore,
│                    #   Explanation, AuditLog, AppUser + RiskAction/AppRole enum'ları)
├── repository/      # Spring Data JPA repository'leri
├── fixture/         # Demo "replay" fixture'ları (DemoTransactionFixture, DemoFixtureLoader)
├── mlservice/       # ml-service REST istemcisi (MlServiceClient, PredictionResult,
│                    #   ExplanationResult, FeatureContribution)
├── kafka/           # TransactionScoringEvent, TransactionEventProducer,
│                    #   TransactionScoringConsumer (ML tarafı), RuleEngineConsumer (Rule Engine tarafı)
├── rules/           # RuleDefinition(Loader), RuleEvaluator (SpEL tabanlı kural motoru)
├── service/         # İş mantığı — TransactionReplayService (üretici), TransactionScoringService
│                    #   (ML tüketicisi), RuleEngineService (Rule Engine tüketicisi),
│                    #   RiskFinalizationService (ikisini escalate-only birleştirir)
├── security/        # JWT (JwtService, JwtAuthenticationFilter, AppUserDetailsService)
├── controller/      # REST endpoint'leri (DemoController, AuthController + DTO'lar)
└── config/          # SecurityConfig, MlServiceConfig

backend/src/main/resources/
├── db/migration/    # Flyway migration'ları (V1: domain şeması, V2: app_users, V3: rule_evaluations)
├── fixtures/        # demo_transactions.json — IEEE-CIS holdout'tan gerçek satırlar
└── rules/           # rules.yaml — Rule Engine'in SpEL koşulları
```

## Mimari Kararlar ve Kavramlar

### 1. Hibrit şema: gerçekçi domain + JSONB feature snapshot

ML modeli IEEE-CIS'in 120 ham/anonim sütunuyla (`V1-339`, `C1-14`, `D1-15`... çoğunun gerçek dünya karşılığı yok) çalışıyor, ama gerçekçi bir bankacılık şeması `users`/`devices`/`merchants`/`transactions` gibi anlamlı alanlar bekler. Çözüm: domain tabloları normalize ve okunaklı kalıyor; `risk_scores.feature_snapshot` (JSONB) modele gönderilen TAM ham vektörü saklıyor. Bu snapshot aynı zamanda `explanations` tablosunun kaynağı — "hangi tam feature vektörüyle bu karar verildi" sorusu denetim için net cevaplanabiliyor.

### 2. Demo veriler DB'ye seed edilmiyor, JSON fixture olarak duruyor

`fixtures/demo_transactions.json`, IEEE-CIS holdout setinden (modele hiç karışmamış, gerçek etiketli) seçilmiş 7 gerçek satır içeriyor: `caught_fraud` (doğru yakalanan), `missed_fraud` (kaçırılan — modelin kör noktası), `false_positive` (yanlış alarm), `ordinary_small/medium/large`, `rule_escalation` (ML tek başına APPROVE diyor ama Rule Engine'in "yeni cihaz + $1000 üzeri tutar" kuralı REVIEW'a yükseltiyor — bkz. aşağıdaki "Uçtan uca doğrulama notu"). Bilinçli bir tasarım kararı: veritabanı pipeline'ın ÇIKTISI olmalı, GİRDİSİ değil — fixture'lar DB'ye önceden yazılsaydı `replay` endpoint'inin "sıfırdan gerçekten çalıştığını" göstermenin bir anlamı kalmazdı. Anonim sütunlar (V/C/D...) sentetik ÜRETİLMEDİ — istatistiksel örnekleme modelin asıl sinyalini gürültüye çevirirdi, bu yüzden gerçek holdout satırları kullanıldı.

### 3. Asenkron akış: iki BAĞIMSIZ paralel Kafka consumer'ı + escalate-only birleştirme

`POST /replay` senkron değil — hemen `202 Accepted` + `{transactionId, status: PENDING}` döner, gerçek skorlama arka planda, İKİ AYRI consumer group'ta PARALEL olarak olur:

1. **`TransactionReplayService.replay()`** (`@Transactional`): User/Device/Merchant find-or-create → Transaction kaydet → `transactions` topic'ine `{transactionId, features}` event'i bas.
2. **ML tarafı** — `TransactionScoringConsumer` (consumer group `fraud-backend`) event'i alır, `TransactionScoringService.score()`'a devreder: `ml-service /predict` çağır → RiskScore kaydet (`action` = SAF ML kararı) → `/explain` çağır → Explanation satırları → bir AuditLog satırı (`actor=SYSTEM`).
3. **Rule Engine tarafı** — `RuleEngineConsumer` (AYRI consumer group `fraud-rule-engine`, aynı topic'in kendi kopyasını alır) event'i alır, `RuleEngineService.evaluate()`'e devreder: `rules.yaml`'daki SpEL koşullarını feature map'e karşı çalıştırır, eşleşen kuralların en ağırını `rule_evaluations`'a yazar.
4. **`RiskFinalizationService.tryFinalize()`** — HER İKİ taraf da kendi işi bitince bunu çağırır (idempotent: ikisi de yazmadıysa no-op, zaten finalize edilmişse no-op). İkisi de yazmışsa escalate-only (`RiskActionSeverity`: BLOCK > REVIEW > APPROVE) ile `risk_scores.final_action`'ı hesaplar. Rule Engine gerçekten bir şeyi değiştirdiyse (`final_action != action`) ikinci bir AuditLog satırı (`actor=RULE_ENGINE`) düşer — değiştirmediyse gürültü eklenmez.
5. **`GET /transactions/{id}`** — `final_action` set edilene kadar `PENDING`, sonra `SCORED` + nihai (escalate edilmiş) `action` döner. Ara durumlar (sadece biri bitmiş) dışarı sızdırılmıyor.

**Kritik detay — "dual write" tuzağı:** `transaction.getId()`'yi aldıktan hemen sonra Kafka'ya basmak YANLIŞ olurdu — DB transaction'ı henüz commit olmadan consumer'lar (aynı JVM'de, çok hızlı) event'i işleyip `transactionRepository.findById()` çağırırsa transaction'ı bulamaz. Çözüm: `TransactionSynchronizationManager.registerSynchronization(...)` ile publish'i `afterCommit()` callback'ine erteliyoruz. `TransactionReplayServiceTest.replay_doesNotPublishUntilTransactionCommits` bunu doğrudan test ediyor.

**Rule Engine — Drools değil, Spring'in kendi SpEL'i:** `rules.yaml`'daki koşullar (`#features['TransactionAmt'] > 5000` gibi) Spring Expression Language ile değerlendiriliyor — zaten classpath'te, yeni bağımlılık yok, ve "configurable YAML/JSON kurallar, Drools gibi ağır bir framework değil" kararıyla tutarlı. Örnek kurallar: büyük tutar (>$5000), yeni cihazdan $1000 üzeri işlem, yüksek riskli merchant (`merchant_risk > 0.5`).

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

45 test **unit test** (Mockito ile DB/ml-service mock'lanıyor) + 2 **entegrasyon testi** (Testcontainers ile gerçek Postgres + gerçek Kafka'ya karşı): `PostgresPersistenceIntegrationTest` (Flyway migration'larının V1-V3 temiz bir DB'de gerçekten çalıştığını ve `RiskScore.featureSnapshot` gibi jsonb alanların Hibernate ile doğru round-trip ettiğini kanıtlar) ve `KafkaRetryAndDeadLetterIntegrationTest` (`KafkaErrorHandlingConfig`'teki retry+DLQ mekanizmasının gerçek bir broker'a karşı çalıştığını otomatik olarak doğrular — `MlServiceClient` bu testte `@MockitoBean` ile deterministik hata üretecek şekilde değiştiriliyor, ml-service'in kendisine bağımlı olmadan). İkisi de aynı static container çiftini paylaşıyor (bkz. `AbstractIntegrationTest`).

### 7. Gerçek transaction ingestion — `POST /api/transactions`

`/api/demo/replay/{scenarioId}` sadece 7 sabit senaryoyu tetikleyebiliyordu — "gerçek bir ingestion API'si yok" bilinçli bir sınırlılık olarak belgelenmişti. Bunu kapatırken karşılaşılan asıl soru teknik değil, VERİYLE ilgiliydi: `ml/README.md`'deki feature seçimi deneyi, modelin gücünün büyük kısmının IEEE-CIS'in ~100 anonim `V`/`C`/`D` sütunundan geldiğini gösteriyor — sadece "gerçekçi"/formda doldurulabilecek 9 türetilmiş özellikle PR-AUC **0,1377**'ye (rastgele düzeyine) düşüyor. Bu anonim sütunlar orijinal veri setinin nasıl anonimleştirildiği bilinmediği için sıfırdan yeniden hesaplanamaz.

Bu yüzden `POST /api/transactions`, "kullanıcı formdan elle bir işlem girer" MODELİNİ benimsemedi — bunun yerine gerçek sistemlerdeki asıl örüntüyü yansıtıyor: fraud servisi feature'ları kendisi hesaplamaz, ayrı bir feature store/pipeline'dan TAM hesaplanmış olarak alır. Çağıran taraf `SubmitTransactionRequest` ile hem gerçekçi domain alanlarını (merchant/tutar/cihaz/konum, `@Valid` ile doğrulanır) HEM DE tam ~120 sütunluk feature vektörünü sağlıyor; bu noktadan sonra `TransactionReplayService.ingest()`, `replay()` ile AYNI paylaşılan `accept()` metoduna düşüyor — fixture'a özel hiçbir kısayol yok, aynı Kafka→ML+Rules→escalate-only-merge pipeline'ından geçiyor. Canlı doğrulandı: `caught_fraud` fixture'ının feature vektörü farklı domain alanlarıyla (`Live Ingestion Test Merchant`, Denver, $777,77) gönderildi, `/api/demo/replay`'e hiç dokunmadan aynı `BLOCK`/`%99,995` sonucunu üretti.

**Bilinçli olarak yapılmayan:** `amount` (domain, `transactions` tablosuna yazılır) ile `features.TransactionAmt` (modele giden ham değer) arasında otomatik senkronizasyon YOK — tutarlılık çağıranın sorumluluğunda (fixture'larda da aynı ayrım zaten vardı, yeni bir tutarsızlık değil).

## Veritabanı Şeması

| Tablo | Amaç |
|---|---|
| `users`, `devices`, `merchants`, `transactions` | Gerçekçi bankacılık domain'i |
| `risk_scores` | `action` = SAF ML kararı, `final_action` = ML+Rule Engine escalate-only birleşimi (ikisi bitene kadar NULL), `feature_snapshot` (JSONB), `base_value` (SHAP taban değeri) |
| `rule_evaluations` | Rule Engine'in ML'den bağımsız kendi kararı + eşleşen kural adları (`transaction_id` UNIQUE) |
| `explanations` | Bir risk_score'a bağlı SHAP katkıları (uzun/long format — feature sayısı şemayı etkilemez) |
| `audit_log` | Denetim izi — ML kararı için bir satır (`actor=SYSTEM`), Rule Engine gerçekten escalate ettiyse ikinci bir satır (`actor=RULE_ENGINE`) |
| `app_users` | JWT ile giriş yapan analist/admin hesapları (banking müşterisiyle KARIŞTIRILMAMALI) |
| `analyst_reviews` | Bir analistin REVIEW durumundaki bir işlem için kararı (`APPROVED`/`REJECTED` + not) — `risk_scores.final_action`'ın ÜZERİNE YAZMAZ, ayrı bir bilgi katmanı (`transaction_id` UNIQUE, ikinci gönderim mevcut satırı günceller) |

## API Uç Noktaları

| Method & Path | Auth | Açıklama |
|---|---|---|
| `POST /api/auth/login` | Açık | `{username, password}` → `{token}` |
| `GET /api/demo/scenarios` | Bearer JWT | Fixture senaryolarının listesi |
| `POST /api/demo/replay/{scenarioId}` | Bearer JWT | Bir senaryoyu tetikler — `202` + `{transactionId, status: PENDING}` döner |
| `GET /api/demo/transactions/{id}` | Bearer JWT | Polling için hafif durum sorgusu — `PENDING` ya da `SCORED` + `fraudProbability/action/modelVersion` (`action` = ML+Rule Engine'in nihai/escalate edilmiş kararı) |
| `GET /api/demo/transactions/{id}/detail` | Bearer JWT | İşlem Detayı sayfası için tek seferlik, zengin cevap — merchant/tutar/konum + ML kararı + Rule Engine kararı + nihai karar + tüm SHAP katkıları + varsa analist kararı. Polling uç noktasından bilinçli olarak ayrı (her 1.5sn'de SHAP çekmek gereksiz yük olurdu) |
| `POST /api/demo/transactions/{id}/review` | Bearer JWT | Bir analistin REVIEW durumundaki bir işlem için kararı (`{decision, note}`). Sadece nihai karar REVIEW ise kabul edilir, aksi halde `409 Conflict`. `reviewedBy` istemciden değil, JWT'deki kimlikten alınır |
| `POST /api/transactions` | Bearer JWT | `/api/demo/**`'den bilinçli olarak AYRI — sabit bir fixture'a bakmaz, çağıran TAM ~120 sütunluk feature vektörünü kendisi sağlar (bkz. aşağıdaki "Gerçek transaction ingestion" bölümü). `@Valid` ile alan bazlı doğrulama (`400` + RFC 7807 hatalı istekte), sonrası `/replay` ile AYNI gerçek pipeline (Kafka→ML+Rules→escalate-only merge) |

## Çalıştırma

```bash
# 1. Postgres + Kafka (dev)
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

# Bir senaryoyu tetikle (hemen PENDING döner)
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/demo/replay/caught_fraud
# {"transactionId":23,"status":"PENDING"}

# Birkaç saniye sonra sonucu sorgula (Kafka consumer arka planda işledi)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/demo/transactions/23
# {"transactionId":23,"status":"SCORED","fraudProbability":0.99995,"action":"BLOCK",...}
```

## Test

```bash
./mvnw test
```

58 test. 56'sı unit test (Docker/DB gerektirmez): `DemoFixtureLoaderTest`, `TransactionReplayServiceTest` (find-or-create mantığı, event'in ancak commit SONRASI basıldığı, `ingest()`'in `transactionTime` verilmezse şimdiki zamana varsayılan gelmesi), `TransactionScoringServiceTest` (ml-service çağrıları, feature_snapshot/explanations/audit_log yazımı, finalize tetikleme, durum sorgusu, `getDetail`'in PENDING/SCORED/analist kararı durumları, `submitReview`'ın durum validasyonu + üzerine yazma davranışı), `RuleEngineServiceTest`, `RiskFinalizationServiceTest` (escalate-only mantığın TÜM kombinasyonları + idempotency + audit_log yalnızca escalation olduğunda), `RuleEvaluatorTest` (SpEL, çoklu kural eşleşmesi, eksik feature güvenliği), `RuleDefinitionLoaderTest`, Kafka producer/consumer testleri, `JwtServiceTest`, `AppUserDetailsServiceTest`, `GlobalExceptionHandlerTest`, `ResilienceConfigTest` (circuit breaker'ın gerçekten CLOSED→OPEN geçtiğini ve OPEN'ken çağrıyı anında reddettiğini doğrular). 2'si Testcontainers ile gerçek Postgres+Kafka'ya karşı çalışan entegrasyon testi (Docker gerektirir, ~30sn) — bkz. yukarıdaki Test Stratejisi.

**Uçtan uca doğrulama notu:** İlk 6 demo fixture'ının hiçbiri gerçek kural eşiklerini (>$5000, yeni cihaz+>$1000, merchant_risk>0.5) tetiklemiyordu — bu yüzden escalation'ı canlı göstermek için önce `rules.yaml`'daki bir eşik geçici olarak düşürülüp test edildi, sonra geri alındı. Kalıcı çözüm olarak IEEE-CIS holdout setinde bu eşiklere GERÇEKTEN uyan bir satır arandı ve bulundu: `rule_escalation` fixture'ı (`$1.500`, yeni cihaz, gerçekte fraud değil) — ML tek başına `%0,005` olasılıkla APPROVE diyor, ama Rule Engine'in `new_device_meaningful_amount` kuralı REVIEW'a yükseltiyor. Eşik hackleme olmadan, gerçek veriyle, kalıcı olarak doğrulanabiliyor.

## Bilinen Sınırlılıklar / Sıradaki Adımlar

Aşağıdaki beş madde kapatıldı:

- ✅ **Global exception handler** — `exception/GlobalExceptionHandler.java`, tüm hatalar (bizimkiler + Spring'in framework hataları) RFC 7807 `ProblemDetail` formatında dönüyor, stack trace client'a sızmıyor.
- ✅ **Kafka retry/DLQ** — `config/KafkaErrorHandlingConfig.java`, her consumer group için ayrı hata yönetimi (3 deneme + 1sn backoff, sonra group'a özel bir Dead Letter Topic: `transactions.fraud-backend.DLT` / `transactions.fraud-rule-engine.DLT`).
- ✅ **Resilience4j** — `config/ResilienceConfig.java`, ml-service çağrıları etrafında circuit breaker (kasıtlı olarak retry yok, Kafka'nın kendi retry'ıyla çakışmasın diye; fallback yok, DLQ zaten güvenlik ağı).
- ✅ **Testcontainers** — `PostgresPersistenceIntegrationTest` ve `KafkaRetryAndDeadLetterIntegrationTest`, gerçek Postgres+Kafka container'larına karşı çalışıyor.
- ✅ **Gerçek transaction ingestion API'si** — `POST /api/transactions`, bkz. yukarıdaki "Gerçek transaction ingestion" bölümü. Sabit fixture'lara bağımlı olmayan, çağıranın kendi feature vektörünü sağladığı ve AYNI gerçek pipeline'dan geçen bir uç nokta.

Kalan bilinçli sınırlılık:

- **DLQ'ya düşen mesajlar için bir redrive/yeniden işleme aracı yok** — bilinçli olarak kapsam dışı bırakıldı (portfolyo projesi ölçeğinde gereksiz); DLQ'ya düşen bir transaction kalıcı olarak `PENDING` kalır.
