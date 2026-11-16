"""MotNgum POS server: nhận đơn từ app Android (agent), quản lý đơn, in tem ly."""
from __future__ import annotations

import asyncio
import base64
import contextlib
import io
import logging
import os
import time
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Response
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image
from pydantic import BaseModel, Field

from .config import load_config, source_for_package
from .db import DB, item_from_dict
from .label import expand_cups, render_order_labels
from .parser import ParsedOrder, parse_manual, parse_order, split_lines
from .printer import PrinterError, print_images, send

log = logging.getLogger("motngum")
STATIC = Path(__file__).parent / "static"


# ------------------------------------------------------------- models ------
class ItemIn(BaseModel):
    name: str
    qty: int = 1
    options: list[str] = []
    note: str = ""
    price: str = ""


class ManualOrderIn(BaseModel):
    source: str = "manual"
    code: str = ""
    customer: str = ""
    note: str = ""
    items: list[ItemIn] = []
    text: str = Field("", description="Nhập nhanh: '2x Trà sữa | Size L, 50% đường'")
    print_now: bool = True


class StatusIn(BaseModel):
    status: str


class PrintIn(BaseModel):
    labels: list[int] | None = None  # số thứ tự tem (1..n); None = in hết


class NotificationIn(BaseModel):
    package: str
    title: str = ""
    text: str = ""
    big_text: str = ""
    posted_at: float | None = None


class ScreenIn(BaseModel):
    package: str
    lines: list[str]
    captured_at: float | None = None


class ScreenshotIn(BaseModel):
    package: str
    image_base64: str
    lines: list[str] = []  # chữ đọc được kèm theo (nếu có)
    order_id: int | None = None


class ParseIn(BaseModel):
    text: str
    package: str = "com.grab.merchant"


# ---------------------------------------------------------------- app ------
def create_app(cfg: dict[str, Any] | None = None) -> FastAPI:
    cfg = cfg or load_config()
    db = DB(cfg["db_path"])
    data_dir = Path(cfg.get("data_dir", "data"))
    data_dir.mkdir(parents=True, exist_ok=True)

    def require_key(x_api_key: str = Header("")) -> None:
        if x_api_key != cfg["api_key"]:
            raise HTTPException(401, "Sai API key (header X-Api-Key)")

    def source_label(order: dict) -> str:
        for src in cfg.get("sources", {}).values():
            if src.get("name") == order["source"]:
                return src.get("label", "")
        return "" if order["source"] == "manual" else order["source"].upper()[:6]

    def do_print(order: dict, only: list[int] | None = None) -> str:
        images = render_order_labels(order, cfg, source_label(order), only)
        try:
            dest = print_images(images, cfg)
        except PrinterError as e:
            db.log("error", payload=str(e), order_id=order["id"])
            raise
        db.mark_printed(order["id"])
        db.log("print", payload={"dest": dest, "labels": only or "all", "count": len(images)}, order_id=order["id"])
        return dest

    def ingest(package: str, lines: list[str]) -> dict:
        src = source_for_package(cfg, package)
        parsed = parse_order(lines, src["code_patterns"])
        result: dict[str, Any] = {"parsed": parsed.to_dict(), "action": "ignored"}
        if not parsed.code or not parsed.items:
            return result
        order, action = db.upsert_parsed(src["name"], parsed, "\n".join(lines), cfg.get("order_code_reuse_hours", 12))
        result.update(action=action, order=order)
        return result

    async def auto_print_loop() -> None:
        while True:
            await asyncio.sleep(1)
            if not cfg.get("auto_print"):
                continue
            try:
                for order in db.due_for_print(cfg.get("print_delay_seconds", 4)):
                    try:
                        await asyncio.to_thread(do_print, order)
                    except PrinterError as e:
                        log.error("Auto print order %s failed: %s", order["id"], e)
                        db.set_status(order["id"], "new")
                        # tránh in lại liên tục: đánh dấu đã thử
                        db._exec("UPDATE orders SET print_count=-1 WHERE id=?", (order["id"],))
            except Exception:  # noqa: BLE001
                log.exception("auto_print_loop")

    @contextlib.asynccontextmanager
    async def lifespan(_app: FastAPI):
        task = asyncio.create_task(auto_print_loop())
        yield
        task.cancel()

    app = FastAPI(title="MotNgum POS", lifespan=lifespan)
    app.state.cfg, app.state.db = cfg, db

    # ---------------------------------------------------- dashboard ----
    @app.get("/", include_in_schema=False)
    def index() -> FileResponse:
        return FileResponse(STATIC / "index.html")

    app.mount("/static", StaticFiles(directory=STATIC), name="static")

    @app.get("/api/health")
    def health() -> dict:
        return {"ok": True, "printer": cfg["printer"]["transport"], "auto_print": cfg.get("auto_print")}

    @app.get("/api/settings")
    def settings() -> dict:
        safe = {k: v for k, v in cfg.items() if k != "api_key"}
        return safe

    # ------------------------------------------------------- orders ----
    @app.get("/api/orders")
    def list_orders(status: str | None = None, limit: int = 100) -> list[dict]:
        return [_slim(o) for o in db.list(status, limit)]

    @app.get("/api/orders/{order_id}")
    def get_order(order_id: int) -> dict:
        o = db.get(order_id)
        if not o:
            raise HTTPException(404, "Không tìm thấy đơn")
        return o

    @app.post("/api/orders")
    def create_order(body: ManualOrderIn) -> dict:
        if body.text.strip():
            parsed = parse_manual(body.text)
        else:
            parsed = ParsedOrder(items=[item_from_dict(i.model_dump()) for i in body.items])
        parsed.code = (body.code or parsed.code).upper()
        parsed.customer = body.customer or parsed.customer
        parsed.note = body.note or parsed.note
        if not parsed.items:
            raise HTTPException(400, "Đơn chưa có món nào")
        order = db.insert(body.source or "manual", parsed, body.text, status="new")
        if not parsed.code:
            db._exec("UPDATE orders SET code=? WHERE id=?", (f"T{order['id']}", order["id"]))
            order = db.get(order["id"])
        if body.print_now:
            try:
                do_print(order)
            except PrinterError as e:
                raise HTTPException(502, str(e))
        else:
            # đơn tay không in ngay: không để vòng auto-print in
            db._exec("UPDATE orders SET print_count=-1 WHERE id=?", (order["id"],))
        return db.get(order["id"])

    @app.patch("/api/orders/{order_id}")
    def update_status(order_id: int, body: StatusIn) -> dict:
        try:
            o = db.set_status(order_id, body.status)
        except ValueError as e:
            raise HTTPException(400, str(e))
        if not o:
            raise HTTPException(404, "Không tìm thấy đơn")
        return o

    @app.post("/api/orders/{order_id}/print")
    def print_order(order_id: int, body: PrintIn | None = None) -> dict:
        o = db.get(order_id)
        if not o:
            raise HTTPException(404, "Không tìm thấy đơn")
        try:
            dest = do_print(o, body.labels if body else None)
        except PrinterError as e:
            raise HTTPException(502, str(e))
        return {"ok": True, "dest": dest}

    @app.get("/api/orders/{order_id}/labels/{n}.png")
    def label_png(order_id: int, n: int) -> Response:
        o = db.get(order_id)
        if not o or n < 1 or n > len(expand_cups(o)):
            raise HTTPException(404, "Không có tem này")
        img = render_order_labels(o, cfg, source_label(o), [n])[0]
        buf = io.BytesIO()
        img.convert("L").save(buf, "PNG")
        return Response(buf.getvalue(), media_type="image/png")

    @app.get("/api/orders/{order_id}/screenshot")
    def order_screenshot(order_id: int) -> FileResponse:
        o = db.get(order_id)
        if not o or not o["screenshot"] or not os.path.exists(o["screenshot"]):
            raise HTTPException(404, "Không có ảnh chụp")
        return FileResponse(o["screenshot"])

    @app.post("/api/printer/test")
    def printer_test() -> dict:
        demo = {
            "id": 0, "code": "TEST", "source": "manual", "note": "",
            "items": [{"name": "Trà sữa trân châu đường đen", "qty": 1,
                       "options": ["Size L", "50% đường", "Ít đá"], "note": "Tem in thử"}],
        }
        images = render_order_labels(demo, cfg, "MN")
        try:
            return {"ok": True, "dest": print_images(images, cfg)}
        except PrinterError as e:
            raise HTTPException(502, str(e))

    @app.post("/api/printer/calibrate")
    def printer_calibrate() -> dict:
        """Máy in tem TSPL tự dò khe giữa các tem (làm khi mới lắp cuộn tem / đổi khổ tem)."""
        if cfg["printer"].get("language", "tspl") != "tspl":
            raise HTTPException(400, "Chỉ dùng cho máy in tem (language = tspl)")
        lab = cfg["label"]
        data = (f"SIZE {lab['width_mm']} mm,{lab['height_mm']} mm\r\nGAP {lab['gap_mm']} mm,0 mm\r\n"
                "GAPDETECT\r\n").encode()
        try:
            return {"ok": True, "dest": send(data, cfg["printer"], cfg.get("data_dir", "data"))}
        except PrinterError as e:
            raise HTTPException(502, str(e))

    @app.post("/api/parse")
    def parse_preview(body: ParseIn) -> dict:
        src = source_for_package(cfg, body.package)
        return parse_order(body.text, src["code_patterns"]).to_dict()

    @app.get("/api/events")
    def events(limit: int = 50) -> list[dict]:
        return db.events(limit)

    # -------------------------------------------- Android agent API ----
    @app.post("/api/agent/notification", dependencies=[Depends(require_key)])
    def agent_notification(body: NotificationIn) -> dict:
        db.log("notification", body.package, body.model_dump())
        lines = split_lines([body.title, body.text, body.big_text])
        # Một số app ghi đủ món ngay trong thông báo dạng mở rộng -> thử bóc luôn.
        res = ingest(body.package, lines)
        return {"ok": True, "action": res["action"]}

    @app.post("/api/agent/screen", dependencies=[Depends(require_key)])
    def agent_screen(body: ScreenIn) -> dict:
        res = ingest(body.package, body.lines)
        db.log("screen", body.package, {"lines": body.lines, "action": res["action"]},
               res.get("order", {}).get("id") if res.get("order") else None)
        return {"ok": True, "action": res["action"], "parsed": res["parsed"],
                "order_id": res["order"]["id"] if res.get("order") else None}

    @app.post("/api/agent/screenshot", dependencies=[Depends(require_key)])
    def agent_screenshot(body: ScreenshotIn) -> dict:
        try:
            raw = base64.b64decode(body.image_base64)
            img = Image.open(io.BytesIO(raw))
            img.load()
        except Exception:  # noqa: BLE001
            raise HTTPException(400, "Ảnh không hợp lệ")
        shots = data_dir / "screenshots"
        shots.mkdir(parents=True, exist_ok=True)
        path = shots / f"{time.strftime('%Y%m%d-%H%M%S')}-{int(time.time() * 1000) % 1000:03d}.jpg"
        img.convert("RGB").save(path, "JPEG", quality=80)

        lines = list(body.lines)
        ocr_used = False
        if len(lines) < 3:
            ocr = ocr_image(img)
            if ocr:
                lines, ocr_used = split_lines(ocr), True
        res = ingest(body.package, lines) if lines else {"action": "ignored", "parsed": None}
        order_id = body.order_id or (res.get("order") or {}).get("id")
        if order_id:
            db.set_screenshot(order_id, str(path))
        db.log("screenshot", body.package, {"path": str(path), "ocr": ocr_used, "lines": lines}, order_id)
        return {"ok": True, "action": res["action"], "ocr": ocr_used, "order_id": order_id, "parsed": res.get("parsed")}

    return app


def ocr_image(img: Image.Image) -> str:
    """OCR bằng Tesseract nếu có cài (pip install pytesseract + tesseract-ocr-vie)."""
    try:
        import pytesseract  # type: ignore
    except ImportError:
        return ""
    try:
        return pytesseract.image_to_string(img.convert("L"), lang="vie+eng")
    except Exception as e:  # noqa: BLE001
        log.warning("OCR lỗi: %s", e)
        return ""


def _slim(o: dict) -> dict:
    o = dict(o)
    o.pop("raw_text", None)
    return o
