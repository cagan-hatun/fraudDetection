"""Model yükleme ve tahmin mantığı.

Eğitilmiş LightGBM modelini ve serving metadata'sını (özellik listesi,
dtype bilgisi, threshold'lar) diskten okuyup tahmin/karar üretir.
"""
from dataclasses import dataclass
from pathlib import Path
import json

import joblib
import numpy as np
import pandas as pd
import shap


@dataclass
class ModelBundle:
    model: object
    explainer: object
    model_version: str
    final_features: list[str]
    numeric_columns: list[str]
    categorical_columns: list[str]
    category_mappings: dict[str, list[str]]
    thresholds: dict[str, float]


def load_model_bundle(models_dir: str = "models") -> ModelBundle:
    models_path = Path(models_dir)
    model = joblib.load(models_path / "fraud_lightgbm_v1.joblib")
    with open(models_path / "fraud_lightgbm_v1_metadata.json", encoding="utf-8") as f:
        metadata = json.load(f)

    return ModelBundle(
        model=model,
        # TreeExplainer, ağaç yapısını modelden okuyup önceden hesaplama
        # yapar — bunu her /explain isteğinde değil, uygulama başlarken
        # BİR KERE kurup ModelBundle içinde saklıyoruz (predict.py'deki
        # model yükleme ile aynı "tek seferlik kurulum" mantığı).
        explainer=shap.TreeExplainer(model),
        # MLflow registry'deki isim+versiyonu birleştiriyoruz (örn.
        # "fraud-detection-lightgbm-v1") — Spring tarafı risk_scores'a
        # bunu yazacak, model hangi versiyonuyla skorlandığını izlemek için.
        model_version=f"{metadata['model_name']}-v{metadata['model_version']}",
        final_features=metadata["final_features"],
        numeric_columns=metadata["numeric_columns"],
        categorical_columns=metadata["categorical_columns"],
        category_mappings=metadata["category_mappings"],
        thresholds=metadata["thresholds"],
    )


def _build_feature_dataframe(bundle: ModelBundle, features: dict) -> pd.DataFrame:
    """Tek bir işlemin feature değerlerini, eğitim sırasındakiyle AYNI
    dtype/kategori kodlamasıyla tek satırlık bir DataFrame'e çevirir.
    """
    row = {col: features.get(col) for col in bundle.final_features}
    df = pd.DataFrame([row])

    for col in bundle.numeric_columns:
        df[col] = pd.to_numeric(df[col], errors="coerce")

    for col in bundle.categorical_columns:
        # Eğitim sırasındaki AYNI kategori listesini (ve sırasını) kullanarak
        # kodluyoruz — aksi halde tek satırlık bu DataFrame kendi başına,
        # eğitimdeki kodlarla eşleşmeyen YENİ kodlar üretir (sessiz hata).
        # Eğitimde hiç görülmemiş bir değer gelirse (örn. yeni bir tarayıcı
        # dizesi) önce açıkça eksik (NaN) yapıyoruz — pandas'ın yeni
        # sürümlerinde kategori listesinde olmayan değerleri doğrudan
        # Categorical'a vermek hata fırlatacak şekilde değişiyor.
        known = bundle.category_mappings[col]
        values = df[col].where(df[col].isin(known), other=pd.NA)
        df[col] = pd.Categorical(values, categories=known)

    return df[bundle.final_features]


def predict(bundle: ModelBundle, features: dict) -> dict:
    df = _build_feature_dataframe(bundle, features)
    proba = float(bundle.model.predict_proba(df)[:, 1][0])

    t_review = bundle.thresholds["t_review"]
    t_block = bundle.thresholds["t_block"]
    if proba >= t_block:
        action = "BLOCK"
    elif proba >= t_review:
        action = "REVIEW"
    else:
        action = "APPROVE"

    return {
        "fraud_probability": proba,
        "action": action,
        "model_version": bundle.model_version,
        "review_threshold": t_review,
        "block_threshold": t_block,
    }


def explain(bundle: ModelBundle, features: dict) -> dict:
    """Tek bir işlem için SHAP açıklaması üretir.

    03_modeling.ipynb'deki local SHAP analiziyle aynı mantık: TreeExplainer
    her feature'ın bu tahmine (log-odds skalasında) katkısını döndürür.
    `feature_value` olarak isteğin HAM (kategorik kodu değil, orijinal
    string/sayı) değerini kullanıyoruz — açıklamanın okunur kalması için.
    """
    df = _build_feature_dataframe(bundle, features)

    # Bu shap sürümünde LightGBM binary classifier için shap_values tek
    # satır x n_feature'lık 2B bir array döner (predict.py'deki predict_proba
    # gibi, tek satırlık df için tek satır sonuç alıyoruz).
    row_shap_values = bundle.explainer.shap_values(df)[0]
    base_value = float(np.ravel(bundle.explainer.expected_value)[0])

    contributions = [
        {
            "feature_name": col,
            "feature_value": features.get(col),
            "shap_value": float(row_shap_values[i]),
        }
        for i, col in enumerate(bundle.final_features)
    ]

    return {"base_value": base_value, "contributions": contributions}
