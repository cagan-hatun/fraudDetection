"""Validation + Integration testler — gerçek FastAPI uygulamasına, gerçek
(yüklenmiş) modelle, HTTP istekleri atarak uçtan uca doğrular.
"""
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
