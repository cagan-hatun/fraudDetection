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

## Durum

- [x] Repo/monorepo iskeleti
- [ ] Veri seti indirme ve EDA (ml/)
- [ ] Feature engineering
- [ ] Model pipeline ve threshold optimizasyonu
- [ ] SHAP / BDDK-uyumlu açıklama raporu
- [ ] FastAPI ML servisi
- [ ] Spring Boot backend (Transaction API, Rule Engine, Kafka akışı)
- [ ] React dashboard
- [ ] Docker Compose ile uçtan uca entegrasyon
