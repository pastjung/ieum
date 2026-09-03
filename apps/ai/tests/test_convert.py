from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    res = client.get("/api/v1/health")
    assert res.status_code == 200
    assert res.json()["data"]["status"] == "UP"


def test_convert_braille():
    res = client.post("/api/v1/convert/braille", json={"text": "가나다"})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["engineVersion"] == "v2"
    assert len(body["data"]["cells"]) == 3


def test_convert_empty_text_rejected():
    res = client.post("/api/v1/convert/braille", json={"text": " "})
    assert res.status_code == 400
