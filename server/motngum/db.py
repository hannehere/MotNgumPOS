"""Lưu đơn hàng bằng SQLite (1 file, không cần cài database)."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

from .parser import Item, ParsedOrder

SCHEMA = """
CREATE TABLE IF NOT EXISTS orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,
    code TEXT NOT NULL DEFAULT '',
    customer TEXT NOT NULL DEFAULT '',
    note TEXT NOT NULL DEFAULT '',
    items_json TEXT NOT NULL DEFAULT '[]',
    status TEXT NOT NULL DEFAULT 'new',      -- new | printed | done | cancelled
    changed_after_print INTEGER NOT NULL DEFAULT 0,
    print_count INTEGER NOT NULL DEFAULT 0,
    raw_text TEXT NOT NULL DEFAULT '',
    screenshot TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL,
    printed_at REAL
);
CREATE INDEX IF NOT EXISTS idx_orders_code ON orders(source, code, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,          -- notification | screen | screenshot | print | error
    package TEXT NOT NULL DEFAULT '',
    payload TEXT NOT NULL DEFAULT '',
    order_id INTEGER,
    created_at REAL NOT NULL
);
"""

STATUSES = ("new", "printed", "done", "cancelled")


class DB:
    def __init__(self, path: str):
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.conn.executescript(SCHEMA)
            self.conn.commit()

    # ---- helpers -----------------------------------------------------------
    @staticmethod
    def _row(row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        d = dict(row)
        d["items"] = json.loads(d.pop("items_json") or "[]")
        d["cup_count"] = sum(int(i.get("qty", 1)) for i in d["items"])
        d["changed_after_print"] = bool(d["changed_after_print"])
        return d

    def _exec(self, sql: str, args: tuple = ()) -> sqlite3.Cursor:
        with self.lock:
            cur = self.conn.execute(sql, args)
            self.conn.commit()
            return cur

    # ---- orders ------------------------------------------------------------
    def get(self, order_id: int) -> dict | None:
        with self.lock:
            return self._row(self.conn.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone())

    def list(self, status: str | None = None, limit: int = 100) -> list[dict]:
        with self.lock:
            if status:
                rows = self.conn.execute(
                    "SELECT * FROM orders WHERE status=? ORDER BY id DESC LIMIT ?", (status, limit)
                ).fetchall()
            else:
                rows = self.conn.execute("SELECT * FROM orders ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [self._row(r) for r in rows]

    def find_recent(self, source: str, code: str, within_hours: float) -> dict | None:
        if not code:
            return None
        since = time.time() - within_hours * 3600
        with self.lock:
            row = self.conn.execute(
                "SELECT * FROM orders WHERE source=? AND code=? AND created_at>=? ORDER BY id DESC LIMIT 1",
                (source, code, since),
            ).fetchone()
        return self._row(row)

    def insert(self, source: str, order: ParsedOrder, raw_text: str = "", status: str = "new") -> dict:
        now = time.time()
        cur = self._exec(
            "INSERT INTO orders(source, code, customer, note, items_json, status, raw_text, created_at, updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (source, order.code, order.customer, order.note,
             json.dumps([i.__dict__ for i in order.items], ensure_ascii=False), status, raw_text, now, now),
        )
        return self.get(cur.lastrowid)

    def upsert_parsed(self, source: str, order: ParsedOrder, raw_text: str, within_hours: float) -> tuple[dict, str]:
        """Thêm đơn mới hoặc cập nhật đơn đã có (cùng mã, trong N giờ gần đây).

        Trả về (order, action) với action ∈ {created, updated, unchanged, changed_after_print}.
        """
        with self.lock:
            existing = self.find_recent(source, order.code, within_hours)
            if existing is None:
                return self.insert(source, order, raw_text), "created"
            new_items = [i.__dict__ for i in order.items]
            new_cups = order.cup_count
            if new_items == existing["items"] or new_cups < existing["cup_count"]:
                return existing, "unchanged"
            if existing["status"] == "new":
                self._exec(
                    "UPDATE orders SET items_json=?, customer=COALESCE(NULLIF(?,''),customer),"
                    " note=COALESCE(NULLIF(?,''),note), raw_text=?, updated_at=? WHERE id=?",
                    (json.dumps(new_items, ensure_ascii=False), order.customer, order.note, raw_text,
                     time.time(), existing["id"]),
                )
                return self.get(existing["id"]), "updated"
            if new_cups > existing["cup_count"]:
                self._exec(
                    "UPDATE orders SET items_json=?, raw_text=?, changed_after_print=1, updated_at=? WHERE id=?",
                    (json.dumps(new_items, ensure_ascii=False), raw_text, time.time(), existing["id"]),
                )
                return self.get(existing["id"]), "changed_after_print"
            return existing, "unchanged"

    def set_status(self, order_id: int, status: str) -> dict | None:
        if status not in STATUSES:
            raise ValueError(f"Trạng thái không hợp lệ: {status}")
        self._exec("UPDATE orders SET status=?, updated_at=? WHERE id=?", (status, time.time(), order_id))
        return self.get(order_id)

    def mark_printed(self, order_id: int) -> None:
        now = time.time()
        self._exec(
            "UPDATE orders SET status=CASE WHEN status='new' THEN 'printed' ELSE status END,"
            " print_count=print_count+1, printed_at=?, changed_after_print=0 WHERE id=?",
            (now, order_id),
        )

    def set_screenshot(self, order_id: int, path: str) -> None:
        self._exec("UPDATE orders SET screenshot=? WHERE id=?", (path, order_id))

    def due_for_print(self, delay_seconds: float) -> list[dict]:
        cutoff = time.time() - delay_seconds
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM orders WHERE status='new' AND print_count=0 AND items_json!='[]' AND updated_at<=?"
                " ORDER BY id",
                (cutoff,),
            ).fetchall()
        return [self._row(r) for r in rows]

    # ---- events (nhật ký để chỉnh parser) ---------------------------------
    def log(self, kind: str, package: str = "", payload: Any = "", order_id: int | None = None) -> None:
        if not isinstance(payload, str):
            payload = json.dumps(payload, ensure_ascii=False)
        self._exec(
            "INSERT INTO events(kind, package, payload, order_id, created_at) VALUES(?,?,?,?,?)",
            (kind, package, payload[:200_000], order_id, time.time()),
        )

    def events(self, limit: int = 50) -> list[dict]:
        with self.lock:
            rows = self.conn.execute("SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]


def item_from_dict(d: dict) -> Item:
    return Item(
        name=str(d.get("name", "")).strip(),
        qty=max(1, int(d.get("qty", 1) or 1)),
        options=[str(o) for o in d.get("options", []) if str(o).strip()],
        note=str(d.get("note", "") or ""),
        price=str(d.get("price", "") or ""),
    )
