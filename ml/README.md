# Fraud Detection — ML Pipeline

Bu klasör, bir kredi kartı/e-ticaret işlem veri seti üzerinde uçtan uca bir fraud (dolandırıcılık) tespiti makine öğrenmesi pipeline'ını içerir: keşifsel veri analizi, leakage-safe feature engineering, model karşılaştırması, maliyet-duyarlı karar eşiği optimizasyonu ve SHAP ile açıklanabilirlik.

> **Not:** Bu proje bir portfolyö/öğrenme çalışmasıdır, canlı bir üretim sistemine bağlanmamıştır. Buna rağmen, gerçek bir bankacılık senaryosunda izlenecek metodolojik rigor (temporal leakage önleme, maliyet-duyarlı değerlendirme, açıklanabilirlik) hedeflenmiştir.

## İçindekiler

1. [Veri Seti](#veri-seti)
2. [Pipeline Özeti](#pipeline-özeti)
3. [Feature Engineering](#feature-engineering)
4. [Feature Seçimi](#feature-seçimi)
5. [Model Karşılaştırması](#model-karşılaştırması)
6. [Threshold Optimizasyonu](#threshold-optimizasyonu-maliyet-bazlı-karar-politikası)
7. [Açıklanabilirlik (SHAP)](#açıklanabilirlik-shap)
8. [Nihai Sonuçlar](#nihai-sonuçlar-holdout-test)
9. [Bilinen Sınırlılıklar](#bilinen-sınırlılıklar)
10. [Nasıl Çalıştırılır](#nasıl-çalıştırılır)

---

## Veri Seti

[IEEE-CIS Fraud Detection](https://www.kaggle.com/c/ieee-fraud-detection) (Kaggle) — ~590K işlem, %3.5 fraud oranı (aşırı dengesiz). Sütunların çoğu (`V1-339`, `C1-14`, `D1-15`, `M1-9`, `id_01-38`) Kaggle tarafından anonimleştirilmiş; anlamı bilinen sütunlar arasında `TransactionAmt`, `ProductCD`, `card1-6`, `addr1-2`, `P/R_emaildomain`, `DeviceType` bulunuyor.

Veri setinde açık bir `user_id` yok — bu, feature engineering'de önemli bir kısıtlama oldu (aşağıya bakın).

## Pipeline Özeti

| Notebook | İçerik |
|---|---|
| `notebooks/01_eda.ipynb` | Keşifsel veri analizi — sınıf dengesizliği, eksik değer analizi, kategorik/zaman analizleri, korelasyon |
| `notebooks/02_feature_engineering.ipynb` | 9 leakage-safe özelliğin türetilmesi ve doğrulanması |
| `notebooks/03_modeling.ipynb` | Split, feature seçimi, model karşılaştırması, tuning, threshold optimizasyonu, SHAP, nihai değerlendirme |

Ortak fonksiyonlar `src/features.py`'de tanımlı — hem eğitim (notebook) hem de ileride canlı tahmin (FastAPI ML servisi) tarafından kullanılacak şekilde tasarlandı.

## Feature Engineering

Veri setinde açık bir `user_id` olmadığı için, `card1+card2+card3+card5+addr1+D1` kombinasyonundan bir **pseudo-kullanıcı kimliği (`uid`)** türetildi (Kaggle topluluğunda yaygın kabul gören bir yaklaşım — kesin bir kullanıcı ID'si değil, bir yaklaşıklıktır).

Bu `uid` üzerinden, hepsi **leakage-safe** (sadece geçmişe bakan) 9 özellik türetildi:

| Özellik | Açıklama |
|---|---|
| `avg_transaction_amount` | Kullanıcının geçmiş ortalama işlem tutarı |
| `amount_deviation_from_user` | İşlem tutarının bu ortalamadan yüzdesel sapması |
| `transactions_last_10min` / `transactions_last_24h` | Zaman penceresi bazlı işlem sayısı |
| `new_device` | Bu cihaz kullanıcı için daha önce görülmüş mü |
| `new_location` | Bu adres kart ailesi için daha önce görülmüş mü |
| `dist1_deviation_from_user` | `dist1`'in kullanıcı ortalamasından sapması |
| `time_since_last_transaction` | Son işlemden bu yana geçen süre |
| `merchant_risk` | Proxy merchant (`ProductCD+P_emaildomain`) için Bayesian-smoothed geçmiş fraud oranı |

**Kapsam dışı bırakılan:** `failed_attempts_last_hour` — veri seti yalnızca tamamlanmış işlemleri içeriyor, başarısız/reddedilen giriş denemesi kaydı yok. Sentetik bir proxy ile "uydurmak" yerine, gerçek zamanlı bir sistemde authentication servisinden gelecek ayrı bir veri kaynağı gerektirdiği dürüstçe belgelendi.

## Feature Seçimi

Modelde hangi sütunların kullanılacağına **hibrit bir yaklaşımla** karar verildi: 9 türetilmiş özellik + 11 anlaşılır ham sütun (`TransactionAmt`, `ProductCD` vb.) kesin olarak dahil edildi; ~340 anonim sütun (`V`/`C`/`D`/`M`/`id_`) ise LightGBM tabanlı bir tarama + gerçek walk-forward validation PR-AUC karşılaştırmasıyla elendi:

| Feature Set | Sütun Sayısı | Ortalama PR-AUC |
|---|---|---|
| Raw only (mühendislik özellikleri hariç) | 430 | 0.5403 |
| Raw + Engineered (tümü) | 439 | 0.5393 |
| **Top-100 (seçilen)** | **120** | **0.5386** |
| Top-50 | 70 | 0.5254 |
| Engineered only | 9 | 0.1377 |

**Önemli bulgu:** Raw-only ile Raw+Engineered arasındaki fark istatistiksel olarak anlamsız (std'nin çok altında) — 9 özelliğimiz PR-AUC'a ölçülebilir bir katkı eklemedi. Muhtemel sebep: IEEE-CIS'in anonim `V`/`C`/`D` sütunları, bizimkine benzer davranışsal/velocity sinyallerini zaten örtük olarak içeriyor. Bu bulgunun SHAP analiziyle nasıl nüanslandığı için [Açıklanabilirlik](#açıklanabilirlik-shap) bölümüne bakın.

Nihai set olarak **Top-100** seçildi — performans kaybı istatistiksel olarak anlamsız, ama SHAP analizi ve rapor için çok daha yönetilebilir.

## Model Karşılaştırması

**Split metodolojisi:** Rastgele train/test split KULLANILMADI (temporal leakage riski). Bunun yerine:
- Verinin en yeni %15'i **holdout test** olarak ayrıldı (tüm modelleme sürecinde hiç kullanılmadı, sadece en sonda tek seferlik nihai değerlendirme için).
- Kalan %85 üzerinde **walk-forward CV** (`sklearn.model_selection.TimeSeriesSplit`, 5 fold) — her fold'da train penceresi genişliyor, validation her zaman train'in kronolojik sonrasında.

**Birincil metrik:** PR-AUC (`average_precision_score`) — %3.5 fraud oranındaki dengesiz veri setinde ROC-AUC'tan daha güvenilir.

| Model | Ortalama PR-AUC (tuning öncesi) | Tuning sonrası |
|---|---|---|
| Logistic Regression | 0.3845 | (tune edilmedi) |
| Random Forest | 0.5612 | 0.5320 *(kötüleşti)* |
| XGBoost | 0.5044 | (tune edilmedi) |
| **LightGBM** | 0.5386 | **0.5681** *(en iyi)* |
| Isolation Forest (unsupervised) | 0.1104 | — |

Hyperparameter tuning (`RandomizedSearchCV`, `n_iter=8`, walk-forward CV ile) sadece en iyi 2 aday üzerinde (RF, LightGBM) yapıldı — XGBoost ve LR aradaki büyük farkı kapatması olası olmadığı için tune edilmedi.

**Öğretici bulgu:** RF'de tuning işe yaramadı (hatta kötüleşti — bulunan kombinasyon muhtemelen yaprakları fazla genelleştirip fraud'un ince paternlerini kaybetti), LightGBM'de işe yaradı. "Tuning her zaman iyileştirir" varsayımı burada yanlış çıktı; bu yüzden tuning sonrası mutlaka tuning öncesiyle karşılaştırıldı, körü körüne "tuned = daha iyi" varsayılmadı.

**Isolation Forest** (etiketsiz/unsupervised) belirgin şekilde daha zayıf — genel aykırı değerleri buluyor, fraud'a özgü paternleri değil. Bu, supervised yaklaşımın bu problem için neden gerekli olduğuna dair somut bir kanıt.

## Threshold Optimizasyonu (Maliyet Bazlı Karar Politikası)

Model bir olasılık üretiyor; bunu APPROVE/REVIEW/BLOCK kararına çevirmek için threshold'lar gerekiyor. Rastgele (örn. %50) değil, **maliyet matrisi bazlı** optimize edildi.

**Literatür çerçevesi:** Bahnsen ve arkadaşlarının (2013-2015) *example-dependent cost-sensitive* fraud tespiti çerçevesi kullanıldı ([kaynak](https://albahnsen.github.io/files/Cost%20Sensitive%20Credit%20Card%20Fraud%20Detection%20using%20Bayes%20Minimum%20Risk%20-%20Publish.pdf)): FN (fraud'u kaçırma) maliyeti = işlem tutarının kendisi; TP/FP (flag edilen işlem) maliyeti = sabit bir idari maliyet (Ca).

**Bizim 3-aksiyonlu genişletmemiz** (literatürün ötesinde, projenin APPROVE/REVIEW/BLOCK mimarisine özgü): REVIEW ve BLOCK'a farklı idari maliyetler atandı — `C_review=$5`, `C_block=$25` (varsayım, gerçek banka verisi değil — bu yüzden bir duyarlılık analizi de yapıldı). **Eşdeğerlik kanıtlandı:** `t_review=t_block` VE `C_review=C_block` olduğunda, 3-aksiyonlu model matematiksel olarak Bahnsen'in 2-sınıflı modeline eşdeğer (sayısal fark: 0).

**Duyarlılık analizi:**

| Senaryo | Ca_review | Ca_block | t_review | t_block | Yakalanan Fraud |
|---|---|---|---|---|---|
| Düşük | $3 | $15 | 0.11 | 0.99 | %91.1 |
| **Base** | **$5** | **$25** | **0.23** | **0.99** | **%83.0** |
| Yüksek | $10 | $50 | 0.41 | 0.99 | %71.2 |

**Önemli metodolojik bulgu:** `t_block` her üç senaryoda da aynı (0.99, arama aralığının üst sınırı) çıktı. Sebep: REVIEW ve BLOCK'a aynı fraud-durdurma etkinliği (%100) atanmışken REVIEW'un maliyeti her zaman BLOCK'unkinden düşük — bu yüzden BLOCK, tanımlanan maliyet fonksiyonunda **REVIEW tarafından domine ediliyor**. Yani mevcut varsayımlar altında 3-aksiyonlu sistem ekonomik olarak gerçek anlamda 3-aksiyonlu değil. BLOCK'un anlamlı bir rol oynaması için REVIEW'a bir kapasite kısıtı veya %100'den düşük bir yakalama oranı gibi ek operasyonel varsayımlar eklenmesi gerekir — bu, keyfi yeni bir varsayım eklemek istemediğimiz için v1 kapsamında bilinçli olarak yapılmadı.

## Açıklanabilirlik (SHAP)

`shap.TreeExplainer` ile hem global (model geneli) hem local (tek işlem) analiz yapıldı.

**Global bulgu — feature engineering'in daha nüanslı ikinci sınavı:**

| Özellik | SHAP Sırası (120 içinde) |
|---|---|
| `merchant_risk` | **11** — model tarafından gerçekten kullanılıyor |
| `avg_transaction_amount`, `dist1_deviation_from_user`, `time_since_last_transaction`, `amount_deviation_from_user` | 36-44 — orta düzey katkı |
| `transactions_last_24h` | 80 |
| `new_device`, `transactions_last_10min`, `new_location` | 114-119 — neredeyse hiç kullanılmıyor |

Ablation çalışması "9 özellik toplamda PR-AUC'a katkı eklemedi" derken, SHAP bunun tek boyutlu olmadığını gösteriyor: `merchant_risk` net bir kazanım, "yenilik/hız" sinyalleri (`new_device`, `new_location`, `transactions_last_10min`) ise modelin zaten anonim sütunlardan sahip olduğu bilgiyle örtüşüyor. Bu, hangi hipotezin doğrulanıp hangisinin doğrulanmadığını gösteren dürüst bir bulgu.

**Local örnek:** Olasılığı %99.94 olan gerçek bir fraud işleminde, riski en çok artıran ilk 5 etkenden biri `merchant_risk` (+0.77 katkı) — tekil kararlarda da görünür bir rol oynuyor.

Grafikler: `reports/shap_summary.png`, `reports/shap_waterfall_example.png`, `reports/threshold_bahnsen.png`.

## Nihai Sonuçlar (Holdout Test)

Tüm kararlar (model, hiperparametreler, threshold) validation üzerinde kilitlendikten sonra, hiç dokunulmamış holdout test setine **tek seferlik** bakıldı:

| Metrik | Değer |
|---|---|
| PR-AUC | **0.5500** (walk-forward CV ortalaması 0.5681'e çok yakın — aşırı öğrenme yok) |
| Recall | %83.5 |
| Precision | %16.1 |
| Toplam maliyet | **$174,725** |
| Model olmasaydı (naif) maliyet | $469,609 |
| **Tasarruf** | **%62.8** |

**Bağlam:** PR-AUC=0.55, veri setindeki fraud oranına (~%3.5, yani rastgele bir modelin PR-AUC'u ~0.035 olurdu) göre değerlendirilmeli — rastgele tahminden ~15-16 kat daha iyi.

## Bilinen Sınırlılıklar

Bu proje, kısayol yerine dürüst belgelenmiş sınırlılıkları tercih ediyor:

1. **`uid` bir yaklaşıklıktır** — veri setinde açık bir kullanıcı ID'si yok, `card1+card2+card3+card5+addr1+D1` kombinasyonu kesin değil.
2. **`merchant_risk` train/serve tutarsızlığı** — offline değerlendirmede sürekli güncellenen bir risk tablosu simüle ediliyor, ama canlı sistemde periyodik olarak donmuş bir lookup kullanılacak; offline metrikler bu yüzden hafifçe iyimser olabilir.
3. **`failed_attempts_last_hour` kapsam dışı** — veri setinde mevcut değil, gerçek bir sistemde ayrı bir authentication veri kaynağı gerektirir.
4. **Maliyet varsayımları (`Ca`, `Cblock`) gerçek banka verisi değil** — duyarlılık analiziyle sonucun bu varsayımlara ne kadar bağlı olduğu gösterildi.
5. **REVIEW'un %100 etkinlik varsayımı** — gerçek hayatta manuel inceleme her zaman fraud'u yakalamaz; bu basitleştirme, BLOCK'un ekonomik olarak anlamsız çıkmasının doğrudan sebebi.
6. **`dist1_deviation_from_user`'daki `inf` değerleri** — kullanıcının geçmiş `dist1` ortalaması sıfırsa sıfıra bölme oluşuyor, `NaN`'a çevrilip imputation'a bırakıldı.
7. **Hyperparameter tuning hafif** (`RandomizedSearchCV`, `n_iter=8`) — kapsamlı bir grid search değil, zaman/fayda dengesi gözetildi.

## Nasıl Çalıştırılır

```bash
cd ml
python -m venv .venv
.venv/Scripts/activate  # Windows
pip install -r requirements.txt
```

Notebook'lar sırayla çalıştırılmalı: `01_eda.ipynb` → `02_feature_engineering.ipynb` → `03_modeling.ipynb` (veri setinin `data/` klasörüne indirilmiş olması gerekir, bkz. Kaggle API kurulumu).

**Eğitilmiş modeli kullanmak için** (`ml/models/` altında, `.gitignore`'da olduğu için önce notebook'un çalıştırılması gerekir):

```python
import joblib, json

model = joblib.load("models/fraud_lightgbm_v1.joblib")
with open("models/fraud_lightgbm_v1_metadata.json") as f:
    metadata = json.load(f)

proba = model.predict_proba(X[metadata["final_features"]])[:, 1]
action = "BLOCK" if proba >= metadata["thresholds"]["t_block"] else (
    "REVIEW" if proba >= metadata["thresholds"]["t_review"] else "APPROVE"
)
```

MLflow deneylerini görüntülemek için:

```bash
mlflow ui --backend-store-uri sqlite:///mlflow.db
```

**Sıradaki proje fazı:** Bu modeli saran bir FastAPI ML servisi, ardından Spring Boot backend entegrasyonu, Docker Compose ve React frontend.
