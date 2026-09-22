"""Validation + Integration testler — gerçek FastAPI uygulamasına, gerçek
(yüklenmiş) modelle, HTTP istekleri atarak uçtan uca doğrular.
"""
import math

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_docs_available(client):
    response = client.get("/docs")
    assert response.status_code == 200


def test_predict_valid_request(client):
    response = client.post("/predict", json={"TransactionAmt": 500.0, "ProductCD": "W"})
    assert response.status_code == 200
    body = response.json()
    assert 0.0 <= body["fraud_probability"] <= 1.0
    assert body["action"] in ("APPROVE", "REVIEW", "BLOCK")


def test_predict_empty_body_still_valid(client):
    # Tüm alanlar Optional olduğu için boş bir istek de geçerli olmalı
    # (model bunu tamamen eksik veri olarak, NaN'larla değerlendirir).
    response = client.post("/predict", json={})
    assert response.status_code == 200


def test_predict_invalid_type_returns_422(client):
    response = client.post("/predict", json={"TransactionAmt": "elli_tl"})
    assert response.status_code == 422
    body = response.json()
    assert body["detail"][0]["loc"][-1] == "TransactionAmt"


def test_explain_empty_body_still_valid(client):
    response = client.post("/explain", json={})
    assert response.status_code == 200
    body = response.json()
    assert "base_value" in body
    assert len(body["contributions"]) > 0


def test_explain_matches_predict_probability(client):
    # base_value + tüm shap_value'ların toplamı, /predict'in raporladığı
    # olasılığı (sigmoid uygulanmış hali) üretmeli — SHAP'ın modelin
    # gerçek çıktısını doğru parçaladığının uçtan uca kanıtı.
    payload = {"TransactionAmt": 500.0, "ProductCD": "W"}

    predicted_proba = client.post("/predict", json=payload).json()["fraud_probability"]
    explanation = client.post("/explain", json=payload).json()

    margin = explanation["base_value"] + sum(
        c["shap_value"] for c in explanation["contributions"]
    )
    reconstructed_proba = 1 / (1 + math.exp(-margin))

    assert reconstructed_proba == pytest.approx(predicted_proba, abs=1e-4)
