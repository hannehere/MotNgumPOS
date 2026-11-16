import base64
import io

from fastapi.testclient import TestClient
from PIL import Image

from motngum.app import create_app
from motngum.config import DEFAULTS, _merge
from motngum.label import label_size_px, render_label
from motngum.printer import escpos_job, tspl_job

from .test_parser import SCREEN_A


def make(tmp_path, **over):
    cfg = _merge(DEFAULTS, {"db_path": str(tmp_path / "t.db"), "data_dir": str(tmp_path), "api_key": "k", **over})
    return TestClient(create_app(cfg)), cfg


def test_agent_screen_creates_then_updates(tmp_path):
    c, _ = make(tmp_path, auto_print=False)
    h = {"X-Api-Key": "k"}
    assert c.post("/api/agent/screen", json={"package": "com.grab.merchant", "lines": ["x"]}).status_code == 401
    partial = SCREEN_A.split("2x")[0]
    r = c.post("/api/agent/screen", headers=h, json={"package": "com.grab.merchant", "lines": partial.splitlines()}).json()
    assert r["action"] == "created"
    r2 = c.post("/api/agent/screen", headers=h, json={"package": "com.grab.merchant", "lines": SCREEN_A.splitlines()}).json()
    assert r2["action"] == "updated" and r2["order_id"] == r["order_id"]
    r3 = c.post("/api/agent/screen", headers=h, json={"package": "com.grab.merchant", "lines": SCREEN_A.splitlines()}).json()
    assert r3["action"] == "unchanged"
    o = c.get(f"/api/orders/{r['order_id']}").json()
    assert o["cup_count"] == 3 and o["source"] == "grab"


def test_print_dryrun_and_label_png(tmp_path):
    c, _ = make(tmp_path, auto_print=False)
    o = c.post("/api/orders", json={"text": "2x Trà sữa | Size L", "print_now": True}).json()
    assert o["status"] == "printed" and o["code"] == f"T{o['id']}"
    assert list((tmp_path / "prints").glob("*.bin"))
    png = c.get(f"/api/orders/{o['id']}/labels/2.png")
    assert png.status_code == 200 and png.content[:4] == b"\x89PNG"
    assert c.get(f"/api/orders/{o['id']}/labels/3.png").status_code == 404
    r = c.patch(f"/api/orders/{o['id']}", json={"status": "done"}).json()
    assert r["status"] == "done"


def test_auto_print_loop(tmp_path):
    import time
    with TestClient(create_app(_merge(DEFAULTS, {"db_path": str(tmp_path / "t.db"), "data_dir": str(tmp_path),
                                                 "api_key": "k", "print_delay_seconds": 0}))) as c:
        r = c.post("/api/agent/screen", headers={"X-Api-Key": "k"},
                   json={"package": "com.grab.merchant", "lines": SCREEN_A.splitlines()}).json()
        for _ in range(30):
            if c.get(f"/api/orders/{r['order_id']}").json()["status"] == "printed":
                break
            time.sleep(0.2)
        assert c.get(f"/api/orders/{r['order_id']}").json()["status"] == "printed"
        assert len(list((tmp_path / "prints").glob("*.png"))) == 3


def test_screenshot_upload_with_lines(tmp_path):
    c, _ = make(tmp_path, auto_print=False)
    buf = io.BytesIO()
    Image.new("RGB", (40, 80), "white").save(buf, "JPEG")
    r = c.post("/api/agent/screenshot", headers={"X-Api-Key": "k"}, json={
        "package": "com.grab.merchant", "image_base64": base64.b64encode(buf.getvalue()).decode(),
        "lines": SCREEN_A.splitlines()}).json()
    assert r["action"] == "created"
    assert c.get(f"/api/orders/{r['order_id']}/screenshot").status_code == 200


def test_tspl_and_escpos_bytes():
    cfg = DEFAULTS["label"]
    img = render_label({"code": "GF-1", "id": 1, "note": ""},
                       {"name": "Trà sữa", "options": ["Size L"], "note": ""}, 1, 2, cfg, "Shop", "GRAB")
    w, h = label_size_px(cfg)
    assert img.size == (w, h) and w % 8 == 0
    data = tspl_job([img], cfg)
    assert data.startswith(b"SIZE 50 mm,30 mm") and f"BITMAP 0,0,{w // 8},{h},0,".encode() in data
    assert data.endswith(b"PRINT 1,1\r\n")
    e = escpos_job([img])
    assert e.startswith(b"\x1b@\x1dv0\x00")


def test_calibrate_sends_gapdetect(tmp_path):
    c, _ = make(tmp_path, auto_print=False)
    assert c.post("/api/printer/calibrate").json()["ok"]
    assert b"GAPDETECT" in next((tmp_path / "prints").glob("*.bin")).read_bytes()
