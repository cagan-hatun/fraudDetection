"""Pydantic şemaları — request/response veri sözleşmeleri.

120 feature alanını elle tek tek yazmak yerine, model metadata'sından
DİNAMİK olarak üretiyoruz (`pydantic.create_model`) — `final_features`
listesi değişirse şema otomatik güncellenir, elle senkronize etmeye
gerek kalmaz.
"""
from typing import Literal, Optional, Union

from pydantic import BaseModel, create_model

from .model import ModelBundle


def build_transaction_request_model(bundle: ModelBundle) -> type[BaseModel]:
    """Verilen model bundle'ın feature listesinden bir Pydantic modeli üretir.

    Sayısal sütunlar Optional[float], kategorik sütunlar Optional[str] —
    hepsi opsiyonel çünkü eksik değer (None), modelin zaten native olarak
    idare ettiği geçerli bir durum (bkz. features.py'deki NaN felsefesi).
    """
    fields = {}
    for col in bundle.numeric_columns:
        fields[col] = (Optional[float], None)
    for col in bundle.categorical_columns:
        fields[col] = (Optional[str], None)

    return create_model("TransactionFeatures", **fields)


class PredictionResponse(BaseModel):
    fraud_probability: float
    action: Literal["APPROVE", "REVIEW", "BLOCK"]
    model_version: str
    review_threshold: float
    block_threshold: float


class FeatureContribution(BaseModel):
    feature_name: str
    feature_value: Optional[Union[float, str]]
    shap_value: float


class ExplanationResponse(BaseModel):
    base_value: float
    contributions: list[FeatureContribution]
