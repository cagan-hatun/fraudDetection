"""`/explain` endpoint'i.

`predict.py` ile aynı factory-pattern gerekçesi: request şeması model
metadata'sına bağlı olduğu için router, `bundle`/`request_model` elimize
geçtikten sonra (lifespan içinde) inşa ediliyor.
"""
from fastapi import APIRouter

from ..model import ModelBundle, explain as run_explanation
from ..schemas import ExplanationResponse


def create_explain_router(bundle: ModelBundle, request_model: type) -> APIRouter:
    router = APIRouter()

    @router.post("/explain", response_model=ExplanationResponse)
    def explain_endpoint(transaction: request_model):
        features = transaction.model_dump()
        return run_explanation(bundle, features)

    return router
