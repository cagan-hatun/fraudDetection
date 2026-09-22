"""FastAPI uygulaması giriş noktası.

Model + metadata'yı uygulama başlarken (`lifespan`) BİR KERE yüklüyoruz —
her istekte diskten tekrar okumuyoruz. Request modeli (feature listesi)
model metadata'sına bağlı olduğu için, `/predict` router'ı da bu bilgi
elimize geçtikten SONRA (lifespan içinde) inşa edilip uygulamaya ekleniyor.
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .model import load_model_bundle
from .routers.explain import create_explain_router
from .routers.predict import create_predict_router
from .schemas import build_transaction_request_model


@asynccontextmanager
async def lifespan(app: FastAPI):
    bundle = load_model_bundle("models")
    request_model = build_transaction_request_model(bundle)
    app.include_router(create_predict_router(bundle, request_model))
    app.include_router(create_explain_router(bundle, request_model))
    yield
    # Kapanışta serbest bırakılacak bir kaynak yok şu an — ileride örn.
    # bir veritabanı bağlantısı burada kapatılabilir.


app = FastAPI(
    title="Fraud Detection ML Service",
    description="Eğitilmiş LightGBM modelini saran, gerçek zamanlı fraud tahmini/karar servisi.",
    lifespan=lifespan,
)


@app.get("/health")
def health():
    return {"status": "ok"}
