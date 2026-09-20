"""Unit testler — app/model.py'nin mantığını GERÇEK modeli yüklemeden,
izole şekilde test eder (hızlı, dış bağımlılık yok).
"""
import numpy as np
import pandas as pd

from app.model import ModelBundle, _build_feature_dataframe, predict


class StubModel:
    """predict_proba'sı sabit bir değer döndüren sahte model — threshold
    mantığını gerçek LightGBM'i yüklemeden izole test etmek için.

    NumPy array döndürüyoruz (düz Python listesi değil) çünkü gerçek
    sklearn/LightGBM modelleri de öyle dönüyor ve model.py'deki
    `[:, 1]` gibi NumPy-tipi dilimleme sadece array'lerde çalışıyor.
    """

    def __init__(self, fixed_proba: float):
        self.fixed_proba = fixed_proba

    def predict_proba(self, X):
        n = len(X)
        return np.array([[1 - self.fixed_proba, self.fixed_proba]] * n)


def make_bundle(fixed_proba: float = 0.0) -> ModelBundle:
    return ModelBundle(
        model=StubModel(fixed_proba),
        final_features=["TransactionAmt", "ProductCD"],
        numeric_columns=["TransactionAmt"],
        categorical_columns=["ProductCD"],
        category_mappings={"ProductCD": ["C", "H", "R", "S", "W"]},
        thresholds={"t_review": 0.23, "t_block": 0.99, "c_review": 5.0, "c_block": 25.0},
    )


def test_predict_approve_below_review_threshold():
    bundle = make_bundle(fixed_proba=0.10)
    result = predict(bundle, {"TransactionAmt": 100.0, "ProductCD": "W"})
    assert result["action"] == "APPROVE"
    assert result["fraud_probability"] == 0.10


def test_predict_review_between_thresholds():
    bundle = make_bundle(fixed_proba=0.50)
    result = predict(bundle, {"TransactionAmt": 100.0, "ProductCD": "W"})
    assert result["action"] == "REVIEW"


def test_predict_block_at_or_above_block_threshold():
    bundle = make_bundle(fixed_proba=0.99)
    result = predict(bundle, {"TransactionAmt": 100.0, "ProductCD": "W"})
    assert result["action"] == "BLOCK"


def test_build_feature_dataframe_known_category_gets_training_time_code():
    bundle = make_bundle()
    df = _build_feature_dataframe(bundle, {"TransactionAmt": 250.0, "ProductCD": "W"})
    assert df["ProductCD"].iloc[0] == "W"
    assert str(df["ProductCD"].dtype) == "category"
    # category_mappings'te "W" son sırada (index 4) — eğitim sırasındaki
    # AYNI kod burada da üretilmeli, aksi halde model yanlış anlar.
    assert df["ProductCD"].cat.codes.iloc[0] == 4


def test_build_feature_dataframe_unknown_category_becomes_missing():
    bundle = make_bundle()
    df = _build_feature_dataframe(
        bundle, {"TransactionAmt": 250.0, "ProductCD": "DAHA_ONCE_GORULMEMIS"}
    )
    assert pd.isna(df["ProductCD"].iloc[0])


def test_build_feature_dataframe_missing_numeric_becomes_nan():
    bundle = make_bundle()
    df = _build_feature_dataframe(bundle, {"ProductCD": "W"})  # TransactionAmt verilmedi
    assert pd.isna(df["TransactionAmt"].iloc[0])
