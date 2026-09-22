# Fraud Detection — ML Servisi (FastAPI)

`ml/` klasöründe eğitilen LightGBM fraud tespiti modelini saran, gerçek zamanlı tahmin/karar (APPROVE/REVIEW/BLOCK) sunan bir REST API. Mimarideki rolü: Spring Boot backend'i bu servisi REST üzerinden senkron çağırır (bkz. proje kök mimarisi — `Transaction Data → Feature Engineering → ML Service → Risk Engine`).

> Bu servis, zaten hesaplanmış feature değerlerini (bkz. `ml/src/features.py`) alır — ham işlem verisinden feature hesaplama (kullanıcı geçmişi, rolling agregasyonlar) bu servisin kapsamında değil, ayrı bir feature store/pipeline katmanında gerçekleşir.

## Proje Yapısı

```
ml-service/
├── app/
│   ├── main.py              # FastAPI uygulaması, lifespan ile model yükleme
│   ├── model.py              # Model/metadata yükleme + tahmin/açıklama mantığı
│   ├── schemas.py            # Pydantic şemaları (request dinamik üretiliyor)
│   └── routers/
│       ├── predict.py        # POST /predict
│       └── explain.py        # POST /explain (SHAP)
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
- **`/explain` (SHAP)**: `TreeExplainer`, model gibi uygulama başlarken BİR KERE kuruluyor (`ModelBundle.explainer`). Cevap, `base_value` (log-odds taban değeri) + her feature'ın katkısını (`shap_value`) içeriyor — `feature_value` olarak isteğin HAM değeri (kategorik kodu değil) döner, açıklama okunur kalsın diye. `test_explain_matches_predict_probability` testi, `base_value + Σshap_value`'nin sigmoid'inin `/predict`'in olasılığıyla eşleştiğini kanıtlıyor — SHAP'ın modelin çıktısını doğru parçaladığının uçtan uca kanıtı.
- **`/predict` cevabına `model_version` + `review_threshold`/`block_threshold` eklendi**: Spring backend'in `audit_log` tablosu "hangi model versiyonu, hangi eşikle karar verildi" sorusunu cevaplamak zorunda (BDDK/denetim gereksinimi) — bu değerler Spring tarafında AYRI/sabit bir config olarak tutulmuyor, tek doğruluk kaynağı burası (model metadata'sı).

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

15 test: threshold mantığı (APPROVE/REVIEW/BLOCK sınırları), kategori kodlama doğruluğu, eksik değer davranışı, Pydantic'in otomatik tip doğrulaması (422), gerçek modelle uçtan uca `/predict` çağrısı, ve SHAP açıklamalarının (`/explain`) `/predict`'in olasılığıyla tutarlı olduğu.

## Örnek İstek

```bash
curl -X POST http://127.0.0.1:8000/predict \
  -H "Content-Type: application/json" \
  -d '{"TransactionAmt": 500.0, "ProductCD": "W", "card4": "visa"}'
# {"fraud_probability": 0.0595, "action": "APPROVE", "model_version": "fraud-detection-lightgbm-v1", "review_threshold": 0.23, "block_threshold": 0.99}

curl -X POST http://127.0.0.1:8000/explain \
  -H "Content-Type: application/json" \
  -d '{"TransactionAmt": 500.0, "ProductCD": "W", "card4": "visa"}'
# {"base_value": -3.1, "contributions": [{"feature_name": "TransactionAmt", "feature_value": 500.0, "shap_value": 0.42}, ...]}
```

Gönderilmeyen alanlar `null`/eksik kabul edilir — model bunu native olarak (LightGBM `NaN` desteği) idare eder.

## Sıradaki Adımlar

Spring Boot backend bu servisi çağırıyor (bkz. `backend/README.md`); Docker Compose entegrasyonu tüm servisler (Spring, FastAPI, PostgreSQL, Kafka, frontend) hazır olduktan sonra yapılacak.
