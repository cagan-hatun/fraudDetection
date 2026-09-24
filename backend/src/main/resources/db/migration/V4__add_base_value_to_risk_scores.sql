-- SHAP'in taban degerini (base_value) simdiye kadar hic saklamiyorduk;
-- ml-service /explain'den donuyor ama TransactionScoringService onu
-- kullanip atiyordu. Islem Detayi sayfasinin SHAP waterfall grafigi icin
-- gerekli. Eski satirlarda bu bilgi hicbir zaman olmadigindan NULL birakiliyor.
ALTER TABLE risk_scores ADD COLUMN base_value NUMERIC(10, 6);
