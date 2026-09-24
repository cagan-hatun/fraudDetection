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

### 8. DLQ redrive — `POST /api/admin/dlq/{ml-service|rule-engine}/redrive`

`KafkaErrorHandlingConfig`'in DLT'lerine düşen mesajlar (3 başarısız denemeden sonra) kalıcı olarak orada kalıyordu — `DlqRedriveService` bunları AYNI gerçek servis metoduyla (`score()`/`evaluate()`) yeniden işletiyor.

- **Neden Kafka'ya geri yayınlamak yerine doğrudan servis çağrısı:** DLT mesajını ham `transactions` topic'ine geri basmak, HER İKİ consumer group'un da (ML + Rule Engine) mesajı TEKRAR almasına yol açardı — oysa çoğu zaman sadece BİRİ başarısız olmuştur (bkz. canlı doğrulama). Diğer taraf zaten başarılıysa (`rule_evaluations.transaction_id` UNIQUE) ikinci kez işlenmeye çalışılınca constraint ihlaliyle gereksiz bir hataya düşerdi. Redrive bu yüzden DLT'nin AİT OLDUĞU tarafı (hangi DLT'den geldiğini bilerek) doğrudan çağırıyor.
- **Offset takibi elle değil, Kafka'nın kendi mekanizmasıyla:** Her redrive çağrısı kısa ömürlü bir consumer açıyor, kendi KALICI consumer group'unu kullanıyor (`fraud-backend-dlq-redrive` / `fraud-rule-engine-dlq-redrive`). Bir mesaj başarıyla redrive edilince offset'i HEMEN commit ediliyor; bir mesaj başarısız olursa (örn. ml-service hâlâ kapalı) commit edilmeden durulur — o mesaj ve aynı partition'daki sonrakiler DLT'de kalır, BİR SONRAKİ redrive çağrısında tekrar denenir. "Hangi mesajlar zaten redrive edildi" bilgisini elle saklamaya hiç gerek yok.
- **Yetkilendirme — ilk kez gerçek RBAC:** `/api/admin/**` sadece `ROLE_ADMIN` (`SecurityConfig`). Projede o ana kadar her endpoint "geçerli JWT yeter" diyordu; DLQ redrive gibi bir altyapı kurtarma işlemi bir analistin değil bir operasyon sorumlusunun işi olduğu için `AppRole.ADMIN` ilk kez gerçek anlamda kullanılıyor (V6 migration'ı demo bir admin hesabı seed ediyor: `admin`/`ChangeMeAdmin123!`).
- **Canlı doğrulandı:** `ml-service.base-url` geçici olarak geçersiz bir adrese çevrildi, bir işlem tetiklendi, 3 deneme sonrası mesaj `transactions.fraud-backend.DLT`'ye düştüğü Kafka konsol tüketicisiyle doğrulandı (işlem `PENDING` kaldı, `rule_evaluations`'da satır vardı ama `risk_scores`'da yoktu — Rule Engine tarafı bağımsız olarak başarılıydı). `ml-service.base-url` düzeltilip redrive çağrıldığında `{"found":2,"succeeded":2,"failed":0}` döndü, işlem `SCORED`'a geçti; DLT gerçekten boşalmıştı çünkü ikinci bir redrive çağrısı `{"found":0,"succeeded":0,"failed":0}` verdi.
- **Bilinen not — soğuk başlangıç gecikmesi:** Her redrive çağrısı YENİ bir consumer olduğu için sıfırdan bir Kafka group-join el sıkışması gerekiyor; bu ortamda gözlemlenen gecikme ~20 saniyeye kadar çıkabiliyor (`DlqRedriveService`'teki `MAX_CONSECUTIVE_EMPTY_POLLS` bunu tolere edecek şekilde ayarlı). Sık çağrılan bir uç nokta değil, bu yüzden kabul edilebilir bir maliyet.

### 9. Health check — Spring Boot Actuator

`GET /actuator/health` — Docker Compose'un backend/ml-service healthcheck'lerinin dayandığı gerçek uç nokta (`docker-compose.yml`'daki `depends_on: condition: service_healthy` zinciri buna bağlı: postgres→ml-service→backend→frontend, her biri bir öncekinin GERÇEKTEN hazır olduğunu bekliyor, sadece "container başladı" değil).

- **Sadece `health`+`info` dışa açık** (`management.endpoints.web.exposure.include`) — Actuator'ın `env`/`beans`/`threaddump` gibi diğer uç noktaları iç bilgi sızdırabileceği için varsayılan olarak KAPALI; "hepsini aç" kolaylığı bilinçli olarak tercih edilmedi.
- **`/actuator/health` kimlik doğrulama İSTEMİYOR** (`SecurityConfig`'te `permitAll`) — bir orchestrator/Docker healthcheck'i JWT taşımaz, bu yüzden diğer her endpoint'ten farklı bir kural.
- **`show-details=always`:** DB bağlantı durumunu (`{"db":{"status":"UP","details":{"database":"PostgreSQL"}}}`) görünür kılıyor — gerçek bir prod ortamında bu iç detay muhtemelen gizlenirdi (`when-authorized`), ama demo/portfolyo projesinde "servis gerçekten neye bağlı" sorusunu görünür bırakmak daha değerli.
- Canlı doğrulandı: tam container modunda `docker compose up -d --build` çalıştırıldığında ml-service/backend'in `(healthy)` durumuna geçtiği, `depends_on` zincirinin gerçekten bu sırayı beklediği gözlemlendi.

### 10. Rate limiting — `/api/auth/login`

Projenin JWT gerektirmeyen TEK endpoint'i (`permitAll`) olduğu için brute-force denemesine en açık nokta da o — `LoginRateLimitFilter` (bucket4j, token bucket algoritması) IP başına dakikada 5 deneme sınırı koyuyor.

- **Kütüphane tercihi:** JWT'nin aksine (elle yazıldı, "nasıl çalıştığını göster" amacıyla) burada bucket4j gibi endüstri standardı bir kütüphane tercih edildi — token bucket algoritmasının kendisini (burst toleransı, sızdıran kova vb.) yeniden yazmanın öğretici bir değeri yoktu, olgun bir kütüphane kullanmak daha gerçekçi.
- **Ham bir servlet filtresi, MVC handler'ı değil:** `OncePerRequestFilter` olarak yazıldı (JwtAuthenticationFilter ile aynı desen) ve `SecurityConfig`'te `JwtAuthenticationFilter`'dan ÖNCE zincire ekleniyor — bu yüzden `GlobalExceptionHandler` devreye giremiyor, 429 gövdesi elle (RFC 7807 benzeri bir `Map`) yazılıyor.
- **Bellek içi, IP başına ayrı bucket:** `ConcurrentHashMap<String, Bucket>` — tek instance için yeterli (bkz. `ResilienceConfig`'in aynı gerekçesi); birden çok backend instance'ı olsaydı paylaşılan bir store (Redis) gerekirdi.
- Canlı doğrulandı: aynı IP'den art arda 6 giriş denemesi yapıldı, ilk 5'i normal `401`/`200` döndü, 6.'sı `429 Too Many Requests` verdi.

### 11. Idempotency key — `POST /api/transactions`

Bir client isteği retry ederse (ör. ağ hatası, timeout — cevap gelmeden önce bağlantı koptu ama istek aslında işlendi), `Idempotency-Key` header'ı olmadan bu YENİ bir transaction olarak kaydolurdu. Artık aynı key ile gelen ikinci bir istek yeni bir transaction YARATMIYOR, var olanın id'sini + güncel durumunu (`PENDING`/`SCORED`) döndürüyor.

- **Header, body değil:** İstek gövdesi retry'lar arasında teorik olarak farklı olabilir (client'ın kendi hatası), ama "bu işlem daha önce mi denendi" sorusu gövdeden bağımsız bir kimlik — bu yüzden `Idempotency-Key` ayrı bir HTTP header (Stripe vb.'nin de kullandığı yerleşik pattern).
- **Ayrı bir tablo değil, `transactions.idempotency_key` (nullable, UNIQUE) kolonu** (V7 migration) — demo replay akışı hiç kullanmıyor (`null` kalır), sadece gerçek ingestion'a özel.
- **Bilinçli olarak YAPILMAYAN:** Aynı key farklı bir body ile tekrar kullanılırsa (Stripe'ın yaptığı gibi) `409 Conflict` DÖNMÜYOR — mevcut transaction'ı sessizce döndürüyor. Daha "doğru" bir davranış payload'ın hash'ini saklayıp karşılaştırmak olurdu, ama bu ek karmaşıklık portfolyo ölçeğinde gerekli görülmedi.
- **Bilinen sınırlılık — check-then-insert yarışı:** Aynı key ile GERÇEKTEN eşzamanlı iki istek gelirse (ikisi de "bu key yok" kontrolünü aynı anda geçerse), DB'nin UNIQUE kısıtlaması ikinci `INSERT`'i reddeder ama bu zarifçe yakalanıp yeniden sorgulanmıyor — ardışık retry senaryosunda (asıl kullanım amacı) bu risk yok, gerçek eşzamanlı çağrı ihtimali olsaydı bir DB-seviyeli upsert'e taşınması gerekirdi.

## Veritabanı Şeması

| Tablo | Amaç |
|---|---|
| `users`, `devices`, `merchants`, `transactions` | Gerçekçi bankacılık domain'i. `transactions.idempotency_key` (nullable, UNIQUE) — sadece `POST /api/transactions`'ın kullandığı, retry güvenliği için |
| `risk_scores` | `action` = SAF ML kararı, `final_action` = ML+Rule Engine escalate-only birleşimi (ikisi bitene kadar NULL), `feature_snapshot` (JSONB), `base_value` (SHAP taban değeri) |
| `rule_evaluations` | Rule Engine'in ML'den bağımsız kendi kararı + eşleşen kural adları (`transaction_id` UNIQUE) |
| `explanations` | Bir risk_score'a bağlı SHAP katkıları (uzun/long format — feature sayısı şemayı etkilemez) |
| `audit_log` | Denetim izi — ML kararı için bir satır (`actor=SYSTEM`), Rule Engine gerçekten escalate ettiyse ikinci bir satır (`actor=RULE_ENGINE`) |
| `app_users` | JWT ile giriş yapan analist/admin hesapları (banking müşterisiyle KARIŞTIRILMAMALI). `role` (ANALYST/ADMIN) artık gerçek anlamda kullanılıyor — bkz. DLQ redrive'ın `/api/admin/**` kısıtlaması |
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
| `POST /api/transactions` | Bearer JWT | `/api/demo/**`'den bilinçli olarak AYRI — sabit bir fixture'a bakmaz, çağıran TAM ~120 sütunluk feature vektörünü kendisi sağlar (bkz. aşağıdaki "Gerçek transaction ingestion" bölümü). `@Valid` ile alan bazlı doğrulama (`400` + RFC 7807 hatalı istekte), sonrası `/replay` ile AYNI gerçek pipeline (Kafka→ML+Rules→escalate-only merge). Opsiyonel `Idempotency-Key` header'ı — bkz. "Idempotency key" bölümü |
| `POST /api/admin/dlq/ml-service/redrive` | Bearer JWT + `ROLE_ADMIN` | `transactions.fraud-backend.DLT`'deki mesajları yeniden işler, `{found, succeeded, failed}` döner. Analist token'ıyla çağrılırsa `403` |
| `POST /api/admin/dlq/rule-engine/redrive` | Bearer JWT + `ROLE_ADMIN` | Aynısı `transactions.fraud-rule-engine.DLT` için |

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

69 test. 67'si unit test (Docker/DB gerektirmez): `DemoFixtureLoaderTest`, `TransactionReplayServiceTest` (find-or-create mantığı, event'in ancak commit SONRASI basıldığı, `ingest()`'in `transactionTime` verilmezse şimdiki zamana varsayılan gelmesi, idempotency key'in yeni/tekrar kullanılan/SCORED durumundaki davranışı), `TransactionScoringServiceTest` (ml-service çağrıları, feature_snapshot/explanations/audit_log yazımı, finalize tetikleme, durum sorgusu, `getDetail`'in PENDING/SCORED/analist kararı durumları, `submitReview`'ın durum validasyonu + üzerine yazma davranışı), `RuleEngineServiceTest`, `RiskFinalizationServiceTest` (escalate-only mantığın TÜM kombinasyonları + idempotency + audit_log yalnızca escalation olduğunda), `RuleEvaluatorTest` (SpEL, çoklu kural eşleşmesi, eksik feature güvenliği), `RuleDefinitionLoaderTest`, Kafka producer/consumer testleri, `JwtServiceTest`, `AppUserDetailsServiceTest`, `GlobalExceptionHandlerTest`, `ResilienceConfigTest` (circuit breaker'ın gerçekten CLOSED→OPEN geçtiğini ve OPEN'ken çağrıyı anında reddettiğini doğrular), `DlqRedriveServiceTest` (tüm mesajlar başarılı → her offset ayrı commit; bir mesaj başarısız → o ve sonrakiler commit edilmeden bırakılır; iki DLT'nin kendi bağımsız consumer group'unu kullandığı), `LoginRateLimitFilterTest` (ilk 5 deneme geçer, 6.'sı 429, farklı IP'lerin bağımsız limitleri olduğu). 2'si Testcontainers ile gerçek Postgres+Kafka'ya karşı çalışan entegrasyon testi (Docker gerektirir, ~30sn) — bkz. yukarıdaki Test Stratejisi.

**Uçtan uca doğrulama notu:** İlk 6 demo fixture'ının hiçbiri gerçek kural eşiklerini (>$5000, yeni cihaz+>$1000, merchant_risk>0.5) tetiklemiyordu — bu yüzden escalation'ı canlı göstermek için önce `rules.yaml`'daki bir eşik geçici olarak düşürülüp test edildi, sonra geri alındı. Kalıcı çözüm olarak IEEE-CIS holdout setinde bu eşiklere GERÇEKTEN uyan bir satır arandı ve bulundu: `rule_escalation` fixture'ı (`$1.500`, yeni cihaz, gerçekte fraud değil) — ML tek başına `%0,005` olasılıkla APPROVE diyor, ama Rule Engine'in `new_device_meaningful_amount` kuralı REVIEW'a yükseltiyor. Eşik hackleme olmadan, gerçek veriyle, kalıcı olarak doğrulanabiliyor.

## Bilinen Sınırlılıklar / Sıradaki Adımlar

Aşağıdaki altı madde kapatıldı:

- ✅ **Global exception handler** — `exception/GlobalExceptionHandler.java`, tüm hatalar (bizimkiler + Spring'in framework hataları) RFC 7807 `ProblemDetail` formatında dönüyor, stack trace client'a sızmıyor.
- ✅ **Kafka retry/DLQ** — `config/KafkaErrorHandlingConfig.java`, her consumer group için ayrı hata yönetimi (3 deneme + 1sn backoff, sonra group'a özel bir Dead Letter Topic: `transactions.fraud-backend.DLT` / `transactions.fraud-rule-engine.DLT`).
- ✅ **Resilience4j** — `config/ResilienceConfig.java`, ml-service çağrıları etrafında circuit breaker (kasıtlı olarak retry yok, Kafka'nın kendi retry'ıyla çakışmasın diye; fallback yok, DLQ zaten güvenlik ağı).
- ✅ **Testcontainers** — `PostgresPersistenceIntegrationTest` ve `KafkaRetryAndDeadLetterIntegrationTest`, gerçek Postgres+Kafka container'larına karşı çalışıyor.
- ✅ **Gerçek transaction ingestion API'si** — `POST /api/transactions`, bkz. yukarıdaki "Gerçek transaction ingestion" bölümü. Sabit fixture'lara bağımlı olmayan, çağıranın kendi feature vektörünü sağladığı ve AYNI gerçek pipeline'dan geçen bir uç nokta.
- ✅ **DLQ redrive aracı** — `POST /api/admin/dlq/{ml-service|rule-engine}/redrive`, bkz. yukarıdaki "DLQ redrive" bölümü. `ROLE_ADMIN` ile korunuyor, canlı olarak gerçek bir DLQ senaryosuyla (ml-service geçici olarak erişilemez yapılıp) uçtan uca doğrulandı.

Backend artık kendi bilinçli kapsamında bilinen bir sınırlılık taşımıyor.
