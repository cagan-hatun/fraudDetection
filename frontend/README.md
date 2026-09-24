# Fraud Detection — React Frontend

Analistin kullandığı arayüz: demo senaryolarını tetikler, Kafka üzerinden asenkron skorlanan işlemleri canlı takip eder, bir işlemin ML kararını + Rule Engine kararını + SHAP açıklamasını tek ekranda gösterir, REVIEW durumundaki işlemler için analist onay/red akışını sunar. Mimarideki rolü: `backend/README.md`'de anlatılan REST API'nin TEK istemcisi — kendi iş mantığı yok, backend'in ürettiği kararları görselleştirip analiste karar verme arayüzü sağlıyor.

> Bu uygulama backend'e (ve dolaylı olarak Postgres+Kafka+ml-service'e) bağımlı. Çalıştırmadan önce `backend/README.md`'deki adımlarla onu ayağa kaldır.

## Proje Yapısı

```
frontend/src/
├── api/          # client.ts (tipli openapi-fetch client + JWT/401 middleware),
│                 #   schema.d.ts (backend OpenAPI'sinden ÜRETİLİR, elle düzenlenmez),
│                 #   queries.ts (TanStack Query hook'ları), token.ts (localStorage erişimi)
├── auth/         # AuthContext (giriş durumu), ProtectedRoute, jwt.ts (client-side decode)
├── theme/        # MUI teması — tokens.ts (renk/font/spacing sabitleri), index.ts (createTheme)
├── layout/       # AppShell — sidebar+topbar iskeleti, React Router <Outlet/>
├── pages/        # LoginPage, DashboardPage, TransactionRow, SummaryDonutChart,
│                 #   TransactionDetailPage, ShapChart, ReviewPanel
├── utils/        # format.ts — para/tarih/yüzde formatlama (tek yerden, tutarlı)
└── test/         # setup.ts — Vitest + jest-dom global kurulumu
```

## Mimari Kararlar ve Kavramlar

### 1. Tipler backend'den ÜRETİLİR, elle yazılmaz

`npm run gen:api`, backend ayaktayken `http://localhost:8080/v3/api-docs`'tan (Spring'in kendi ürettiği OpenAPI şeması) `src/api/schema.d.ts`'i üretir. `api/client.ts`'teki `openapi-fetch` client'ı bu tiplerle parametrize edilmiş — backend'de bir alan/endpoint değişirse, şemayı yeniden üretip `tsc -b` çalıştırdığımızda derleme zamanında (tip hatası olarak) haberimiz olur, sessizce kırılmaz. `schema.d.ts` git'e commit'li tutuluyor (CI/kurulumda backend'in ayakta olmasına gerek kalmasın diye), ama tek doğruluk kaynağı her zaman backend'in kendisi.

### 2. Polling — durum bazlı `refetchInterval`, `useQueries` ile toplu takip

Bir senaryo tetiklendiğinde backend hemen `202 PENDING` döner, gerçek sonuç Kafka üzerinden arka planda birkaç saniye içinde oluşur. `queries.ts`'teki `transactionStatusOptions`, `refetchInterval`'i sabit değil, mevcut query state'in FONKSİYONU olarak tanımlıyor: durum `SCORED` olunca polling kendiliğinden durur, `PENDING`'ken 1.5sn'de bir devam eder — gereksiz yere sonsuza kadar sorgulama yapılmaz. Dashboard'da aynı anda birden çok işlem tetiklenebildiği için (dinamik uzunlukta bir liste), her satır için ayrı `useQuery` çağırmak React'in Hook kurallarına (döngüde hook çağrılamaz) aykırı olurdu — bunun yerine `useQueries` kullanılıp state page seviyesine çekildi, hem satır bileşenleri hem özet donut grafiği aynı tek polling kaynağından besleniyor.

### 3. JWT — client-side decode SADECE gösterim için, doğrulama değil

`auth/jwt.ts`, token'ın payload'ını (imza doğrulamadan) çözüp sidebar'da kullanıcı adını göstermek için kullanılıyor — güvenlik kararı DEĞİL, backend zaten her isteği kendi tarafında doğruluyor. Token süresi frontend'de KONTROL EDİLMİYOR (bilinçli — bir sonraki maddeye bkz.).

### 4. 401/403'te otomatik çıkış — `AuthContext`'in bilmediği bir başarısızlığı yakalamak için

`AuthContext`'in React state'i "giriş yapılmış" bilgisini token'ın localStorage'da VAR OLMASINDAN çıkarır — token süresi dolduğunda (demo'da 1 saat) bu state hâlâ "giriş yapılmış" der, ama her API çağrısı backend'den sessizce 401/403 döner. `api/client.ts`'teki `onResponse` middleware'i bunu tek yerden yakalayıp token'ı temizler ve `/login`'e yönlendirir — login sayfasının KENDİ 401'ini (yanlış şifre) bununla karıştırmamak için `/api/auth/` isteklerini bilinçli olarak hariç tutuyor.

### 5. İşlem Detayı — polling'den AYRI, zengin bir tek-seferlik uç nokta

Dashboard'daki polling (`GET /transactions/{id}`) bilinçli olarak hafif tutuldu (sadece durum+karar). SHAP katkıları, merchant/konum bilgisi, Rule Engine'in ayrı kararı gibi TransactionDetailPage'in ihtiyaç duyduğu zengin veri `GET /transactions/{id}/detail` ile AYRI bir sorguda çekiliyor — her 1.5 saniyede bir SHAP verisi çekmek gereksiz yük olurdu.

### 6. Tasarım — "Charcoal + Violet", ürettiğimiz kendi paletimiz

Renk paleti ve tipografi (Space Grotesk başlıklarda, IBM Plex Sans gövdede, IBM Plex Mono sayısal/kod verisinde — üçü de `@fontsource` ile self-hosted, harici bir CDN'e bağımlılık yok) birkaç iterasyon sonunda elle seçildi; hazır bir UI kit'in varsayılan temasından KAÇINILDI. MUI teması (`theme/`), APPROVE/REVIEW/BLOCK kararlarını `success`/`warning`/`error` renklerine eşliyor; rozet arka planları sabit bir renk olarak tanımlanmadı, ana renkten `alpha()` ile türetiliyor — light/dark mod arasında geçişte otomatik tutarlı kalıyor.

## Çalıştırma

```bash
# Backend ayaktayken (bkz. backend/README.md), .env dosyasını hazırla:
cp .env.example .env   # VITE_API_BASE_URL=http://localhost:8080

npm install --legacy-peer-deps   # openapi-typescript henüz TS 6'yı peer dep olarak tanımıyor
npm run dev
```

`http://localhost:5173` — demo giriş: `analyst` / `ChangeMe123!` (form önceden dolu gelir).

Backend'de bir alan/endpoint değiştiyse tipleri yeniden üret:

```bash
npm run gen:api   # backend'in ayakta olmasını gerektirir
```

## Test

```bash
npm test
```

21 test: `format.ts` (para/tarih/yüzde formatlama), `jwt.ts` (payload decode), `ProtectedRoute` (giriş yapılmamışken `/login`'e yönlendirme), `LoginPage` (demo hesabın önceden dolu gelmesi, başarılı girişte token'ın `AuthContext`'e yazılması, hatalı girişte uyarı gösterilip `AuthContext`'in DEĞİŞMEMESİ), `ReviewPanel` (form/karar-özeti geçişi, Onayla/Reddet'in doğru `{decision, note}` payload'ıyla `useSubmitReview`'a gönderilmesi, boş not'un `undefined` olarak gönderilmesi, gönderim hatasında uyarı, mevcut bir kararın doğru gösterimi, "Kararı değiştir" ile forma dönüş). İkisi de `api/queries.ts` hook'larını (`useSubmitReview`) ve `api/client.ts`'i `vi.mock` ile izole ediyor — gerçek backend'e bağımlı değil.

## Bilinen Sınırlılıklar / Sıradaki Adımlar

- **`DashboardPage` ve `TransactionDetailPage` hâlâ test edilmedi** — bunlar `useQueries`/polling ağırlıklı, çoğunlukla layout+veri-gösterimi; `LoginPage`/`ReviewPanel`'e kıyasla test değeri daha düşük olduğu için bilinçli olarak önceliklendirilmedi.
- **Bundle boyutu** — production build ~958KB, code-splitting (route bazlı `React.lazy`) hiç yapılmadı; Vite build bunu kendi uyarısı olarak veriyor.
- **Docker Compose'a eklenmedi** — `infra/docker-compose.yml`'da şu an sadece Postgres+Kafka var; bu uygulama şimdilik `npm run dev` ile ayrı çalıştırılıyor (bilinçli olarak düşük öncelikli — prod'a çıkmayacak bir portfolyo projesi).
