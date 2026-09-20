"""`/predict` endpoint'i.

Request modeli (`TransactionFeatures`) uygulama başlarken (main.py'deki
`lifespan` içinde) model metadata'sından dinamik olarak üretildiği için,
router'ı da bir FACTORY fonksiyon olarak tanımlıyoruz — `bundle` ve
`request_model`'i closure ile "yakalayıp" endpoint içinde kullanıyoruz.
"""
from fastapi import APIRouter

from ..model import ModelBundle, predict as run_prediction
from ..schemas import PredictionResponse


def create_predict_router(bundle: ModelBundle, request_model: type) -> APIRouter:
    router = APIRouter()

    @router.post("/predict", response_model=PredictionResponse)
    def predict_endpoint(transaction: request_model):
        features = transaction.model_dump()
        return run_prediction(bundle, features)

    return router
