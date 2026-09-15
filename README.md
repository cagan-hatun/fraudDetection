# Fraud Detection Platform

Bankacılık işlemlerinde dolandırıcılık (fraud) tespiti için uçtan uca bir portfolyö projesi. Proje **canlıya alınmayacak**, CV/portfolyö amaçlı hazırlanmaktadır.

> Detaylı kurulum adımları, mimari diyagramlar ve model performans sonuçları ML pipeline'ı ve backend/frontend implementasyonu ilerledikçe bu README'ye eklenecektir.

## Mimari

**ML pipeline:**
Transaction Data → Feature Engineering → (Supervised Fraud Classifier + Unsupervised Anomaly Detection) → Risk Score Engine → APPROVE/REVIEW/BLOCK → Explanation/Analysis

**Sistem mimarisi:**
Spring Boot → Transaction API → PostgreSQL → Kafka → (ML Service [Python/FastAPI] + Rule Engine [Java/Spring]) → Risk Engine → APPROVE/REVIEW/BLOCK

## Repo yapısı (monorepo)

```
fraudDetection/
├── backend/    # Spring Boot: Transaction API, Rule Engine, Kafka producer/consumer, JWT auth
├── ml/         # Veri analizi, feature engineering, model eğitimi, FastAPI ML servisi
├── frontend/   # React + TypeScript dashboard
└── infra/      # Docker Compose ve diğer altyapı tanımları
```

## Teknoloji özeti

| Katman | Teknoloji |
|---|---|
| Backend | Spring Boot, Spring Security (JWT), Kafka, PostgreSQL, Flyway, springdoc-openapi, Resilience4j |
| ML | Python, scikit-learn / XGBoost / LightGBM, Isolation Forest, SHAP, MLflow, FastAPI |
| Frontend | React, TypeScript, Material UI, Recharts, TanStack Query |
| Altyapı | Docker Compose |
| Veri seti | IEEE-CIS Fraud Detection (Kaggle) |

## Notlar

- Veri setinde açık bir `user_id` yok; kullanıcı bazlı özellikler için `card1+card2+card3+card5+addr1+D1` kombinasyonundan türetilen bir pseudo-kimlik (`uid`) kullanılır. Bu kesin bir kullanıcı ID'si değil, bir yaklaşıklıktır.
- `failed_attempts_last_hour` özelliği kapsam dışı bırakıldı — bu veri seti yalnızca tamamlanmış işlemleri içeriyor, başarısız/reddedilen giriş denemesi kaydı barındırmıyor. Gerçek bir üretim sisteminde bu özellik authentication servisinden ayrı bir veri kaynağı olarak gelmesi gerekir.

## Durum

- [x] Repo/monorepo iskeleti
- [x] Veri seti indirme ve EDA (ml/)
- [ ] Feature engineering (devam ediyor — uid, avg_transaction_amount, amount_deviation_from_user, transactions_last_10min/24h tamamlandı)
- [ ] Model pipeline ve threshold optimizasyonu
- [ ] SHAP / BDDK-uyumlu açıklama raporu
- [ ] FastAPI ML servisi
- [ ] Spring Boot backend (Transaction API, Rule Engine, Kafka akışı)
- [ ] React dashboard
- [ ] Docker Compose ile uçtan uca entegrasyon
