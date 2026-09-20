# Fraud Detection — ML Servisi (FastAPI)

`ml/` klasöründe eğitilen LightGBM fraud tespiti modelini saran, gerçek zamanlı tahmin/karar (APPROVE/REVIEW/BLOCK) sunan bir REST API. Mimarideki rolü: Spring Boot backend'i bu servisi REST üzerinden senkron çağırır (bkz. proje kök mimarisi — `Transaction Data → Feature Engineering → ML Service → Risk Engine`).

> Bu servis, zaten hesaplanmış feature değerlerini (bkz. `ml/src/features.py`) alır — ham işlem verisinden feature hesaplama (kullanıcı geçmişi, rolling agregasyonlar) bu servisin kapsamında değil, ayrı bir feature store/pipeline katmanında gerçekleşir.

## Proje Yapısı

```
ml-service/
├── app/
│   ├── main.py              # FastAPI uygulaması, lifespan ile model yükleme
│   ├── model.py              # Model/metadata yükleme + tahmin mantığı
│   ├── schemas.py            # Pydantic şemaları (request dinamik üretiliyor)
│   └── routers/
│       └── predict.py        # POST /predict
├── models/                   # Eğitilmiş model + metadata (ml/models/'dan kopyalanır, git'e girmez)
├── tests/
│   ├── test_model.py         # Unit testler (mock model, izole)
│   └── test_api.py           # Validation + integration testler (TestClient)
└── requirements.txt
```

## Neden Böyle Tasarlandı

- **Dinamik Pydantic şeması** (`schemas.py`): Model 120 feature kullanıyor; bunları elle 120 satır yazmak yerine `pydantic.create_model()` ile `models/*_metadata.json`'dan runtime'da üretiyoruz — model yeniden eğitilip feature listesi değişirse API şeması otomatik senkronize olur.
- **`lifespan` ile tek seferlik model yükleme**: Model + metadata uygulama başlarken bir kere diskten okunuyor, her istekte tekrar yüklenmiyor. `/predict` router'ı da bu yüzden (request şeması model metadata'sına bağlı olduğu için) `lifespan` içinde, model yüklendikten SONRA inşa edilip ekleniyor.
- **Kategori kodlama tutarlılığı**: LightGBM kategorik sütunları pandas'ın ürettiği tamsayı kodları üzerinden işliyor. Eğitim sırasındaki tam kategori listesi (`category_mappings`) metadata'da saklanıyor; serving tarafı bunu kullanarak eğitimdekiyle BİREBİR aynı kodlamayı üretiyor — aksi halde (örn. tek satırlık bir DataFrame'i doğrudan `.astype("category")` yapmak) sessizce yanlış tahminlere yol açardı.

## Çalıştırma

```bash
cd ml-service
python -m venv .venv
.venv/Scripts/activate  # Windows
pip install -r requirements.txt

# Model dosyalarını ml/models/'dan kopyala (bir kere, model her güncellendiğinde tekrar)
cp ../ml/models/fraud_lightgbm_v1.joblib ../ml/models/fraud_lightgbm_v1_metadata.json models/

uvicorn app.main:app --reload
```

Swagger UI: http://127.0.0.1:8000/docs — `/predict`'i interaktif olarak deneyebilirsin, 120 alanlı form otomatik üretilir.

## Test

```bash
pytest tests/ -v
```

11 test: threshold mantığı (APPROVE/REVIEW/BLOCK sınırları), kategori kodlama doğruluğu, eksik değer davranışı, Pydantic'in otomatik tip doğrulaması (422), ve gerçek modelle uçtan uca `/predict` çağrısı.

## Örnek İstek

```bash
curl -X POST http://127.0.0.1:8000/predict \
  -H "Content-Type: application/json" \
  -d '{"TransactionAmt": 500.0, "ProductCD": "W", "card4": "visa"}'
# {"fraud_probability": 0.0595, "action": "APPROVE"}
```

Gönderilmeyen alanlar `null`/eksik kabul edilir — model bunu native olarak (LightGBM `NaN` desteği) idare eder.

## Sıradaki Adımlar

Spring Boot backend bu servisi çağıracak; Docker Compose entegrasyonu tüm servisler (Spring, FastAPI, PostgreSQL, Kafka, frontend) hazır olduktan sonra yapılacak.
