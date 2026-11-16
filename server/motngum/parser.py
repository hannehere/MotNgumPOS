"""Bóc tách thông tin đơn hàng từ chữ đọc được trên màn hình app bán hàng.

Đầu vào là danh sách các dòng chữ (lấy từ cây Accessibility của app GrabMerchant
hoặc từ OCR ảnh chụp màn hình). Bố cục màn hình của mỗi app có thể thay đổi theo
phiên bản, nên parser dùng heuristic + từ khoá; khi Grab đổi giao diện chỉ cần
chỉnh các danh sách từ khoá bên dưới (hoặc thêm regex vào config.json).
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import asdict, dataclass, field
from typing import Iterable


@dataclass
class Item:
    name: str
    qty: int = 1
    options: list[str] = field(default_factory=list)
    note: str = ""
    price: str = ""


@dataclass
class ParsedOrder:
    code: str = ""
    customer: str = ""
    note: str = ""
    items: list[Item] = field(default_factory=list)

    @property
    def cup_count(self) -> int:
        return sum(i.qty for i in self.items)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["cup_count"] = self.cup_count
        return d


# ---- Regex & từ khoá -------------------------------------------------------
_CUR = r"(?:₫|đ|d|vnd|vnđ|k)"
RE_PRICE_ONLY = re.compile(rf"^[-+]?\s*\d{{1,3}}(?:[.,\s]\d{{3}})*(?:[.,]\d+)?\s*{_CUR}?$", re.I)
RE_TRAILING_PRICE = re.compile(rf"\s+[-+]?\d{{1,3}}(?:[.,]\d{{3}})+\s*{_CUR}?$|\s+\d+\s*{_CUR}$", re.I)
RE_QTY_FIRST = re.compile(r"^(\d{1,2})\s*[xX×*]\s*(\S.*)$")
RE_QTY_ONLY = re.compile(r"^(?:[xX×]\s*)?(\d{1,2})\s*[xX×]?$")  # "2x", "2 x", "x2"
RE_NAME_QTY = re.compile(r"^(\S.*?)\s+[xX×]\s*(\d{1,2})$")
RE_NOTE = re.compile(r"^(?:ghi\s*chú|lưu\s*ý|note|notes|special instructions?)\s*(?:của\s+\S+(?:\s+\S+)?)?\s*[:：]?\s*(.*)$", re.I)
RE_CUSTOMER = re.compile(r"^(?:khách\s*hàng|khách|tên khách|customer|người nhận)\s*[:：]?\s*(.*)$", re.I)
RE_BULLET = re.compile(r"^[\s•·\-–+*▪◦>]+")

# Gặp các dòng này nghĩa là đã hết danh sách món.
STOP_WORDS = [
    "tổng", "tạm tính", "thành tiền", "total", "subtotal", "thanh toán", "payment",
    "phí", "fee", "khuyến mãi", "promo", "chiết khấu", "discount", "giảm giá",
    "tiền mặt", "cash", "thu khách", "doanh thu", "net",
]
# Các dòng giao diện (nút bấm, tiêu đề) cần bỏ qua.
IGNORE_EXACT = {
    "chấp nhận", "accept", "từ chối", "reject", "decline", "in", "print", "in hóa đơn",
    "in hoá đơn", "chi tiết đơn hàng", "order details", "chi tiết", "món", "items", "item",
    "xem thêm", "see more", "đóng", "close", "quay lại", "back", "đơn hàng mới", "new order",
    "đơn mới", "sẵn sàng", "ready", "đánh dấu sẵn sàng", "mark as ready", "giao hàng",
    "delivery", "tự đến lấy", "self pick-up", "pickup", "liên hệ", "chat", "gọi", "call",
    "món ăn", "danh sách món", "số lượng", "qty", "grabfood", "grabmart", "đã nhận", "x",
}
OPTION_HINTS = [
    "size", "đường", "đá", "sugar", "ice", "topping", "thêm", "ít", "nhiều", "không",
    "nóng", "lạnh", "%", "trân châu", "thạch", "kem", "pudding", "upsize",
]


def _norm(s: str) -> str:
    s = unicodedata.normalize("NFC", s or "")
    s = s.replace(" ", " ").replace("​", "")
    return re.sub(r"[ \t]+", " ", s).strip()


def _low(s: str) -> str:
    return _norm(s).lower()


def _is_stop(line: str) -> bool:
    l = _low(line)
    return any(l == w or l.startswith(w + " ") or l.startswith(w + ":") or l.startswith(w + " (") for w in STOP_WORDS)


def _is_ignored(line: str) -> bool:
    l = _low(line).rstrip(":")
    return l in IGNORE_EXACT or RE_PRICE_ONLY.match(l) is not None


def _strip_price(text: str) -> tuple[str, str]:
    m = RE_TRAILING_PRICE.search(text)
    if m:
        return text[: m.start()].strip(), m.group(0).strip()
    return text.strip(), ""


def find_code(lines: Iterable[str], patterns: Iterable[str]) -> str:
    text = "\n".join(lines)
    for pat in patterns:
        m = re.search(pat, text)
        if m:
            code = m.group(1) if m.groups() else m.group(0)
            return code.strip().upper()
    return ""


def split_lines(raw: str | Iterable[str]) -> list[str]:
    if isinstance(raw, str):
        raw = raw.splitlines()
    out: list[str] = []
    for chunk in raw:
        for line in str(chunk).splitlines():
            line = _norm(line)
            if line:
                out.append(line)
    return out


def parse_order(raw: str | Iterable[str], code_patterns: Iterable[str] = ()) -> ParsedOrder:
    lines = split_lines(raw)
    order = ParsedOrder(code=find_code(lines, code_patterns))

    cur: Item | None = None
    pending_qty: int | None = None
    expect: str | None = None  # "note" | "customer" khi nhãn và giá trị nằm ở 2 dòng khác nhau
    in_items = False
    done = False

    def push(item: Item) -> None:
        nonlocal cur
        order.items.append(item)
        cur = item

    for line in lines:
        if order.code and _low(line) == order.code.lower():
            continue

        if expect:
            target = expect
            expect = None
            if not _is_ignored(line) and not _is_stop(line):
                if target == "note":
                    if cur and not done:
                        cur.note = (cur.note + "; " + line).strip("; ")
                    else:
                        order.note = (order.note + "; " + line).strip("; ")
                else:
                    order.customer = order.customer or line
                continue

        m = RE_NOTE.match(line)
        if m:
            val = m.group(1).strip()
            if not val:
                expect = "note"
            elif cur and not done:
                cur.note = (cur.note + "; " + val).strip("; ")
            else:
                order.note = (order.note + "; " + val).strip("; ")
            continue

        m = RE_CUSTOMER.match(line)
        if m and not in_items:
            if m.group(1).strip():
                order.customer = order.customer or m.group(1).strip()
            else:
                expect = "customer"
            continue

        if done:
            continue

        if in_items and _is_stop(line):
            done = True
            cur = None
            continue

        # "2x" / "x2" ở một dòng riêng, tên món ở dòng sau
        m = RE_QTY_ONLY.match(line)
        if m and ("x" in line.lower() or "×" in line) and 0 < int(m.group(1)) < 100:
            pending_qty = int(m.group(1))
            continue

        if _is_ignored(line):
            continue

        if pending_qty is not None:
            name, price = _strip_price(RE_BULLET.sub("", line))
            push(Item(name=name, qty=pending_qty, price=price))
            pending_qty = None
            in_items = True
            continue

        m = RE_QTY_FIRST.match(line)
        if m:
            name, price = _strip_price(m.group(2))
            if name and not RE_PRICE_ONLY.match(name):
                push(Item(name=name, qty=int(m.group(1)), price=price))
                in_items = True
                continue

        m = RE_NAME_QTY.match(line)
        if m and not RE_BULLET.match(line):
            push(Item(name=m.group(1).strip(), qty=int(m.group(2))))
            in_items = True
            continue

        if cur is not None:
            opt, _ = _strip_price(RE_BULLET.sub("", line))
            if opt and not _is_ignored(opt):
                cur.options.append(opt)

    # Bỏ món rỗng / trùng lặp do ảnh chụp chồng nhau
    order.items = [i for i in order.items if i.name]
    return order


def looks_like_option(text: str) -> bool:
    l = _low(text)
    return any(h in l for h in OPTION_HINTS)


def parse_manual(text: str) -> ParsedOrder:
    """Cú pháp nhập tay: mỗi dòng 1 món, ví dụ
    `2x Trà sữa trân châu | Size L, 50% đường | ghi chú: ít ngọt`.
    Dòng bắt đầu bằng '#' là mã đơn, dòng 'ghi chú:' là ghi chú đơn."""
    order = ParsedOrder()
    for raw in split_lines(text):
        if raw.startswith("#"):
            order.code = raw.lstrip("#").strip().upper()
            continue
        m = RE_NOTE.match(raw)
        if m and "|" not in raw:
            order.note = m.group(1).strip()
            continue
        parts = [p.strip() for p in raw.split("|")]
        head = parts[0]
        qty = 1
        mq = RE_QTY_FIRST.match(head) or None
        if mq:
            qty, head = int(mq.group(1)), mq.group(2).strip()
        else:
            mn = RE_NAME_QTY.match(head)
            if mn:
                head, qty = mn.group(1).strip(), int(mn.group(2))
        item = Item(name=head, qty=qty)
        for p in parts[1:]:
            mn = RE_NOTE.match(p)
            if mn:
                item.note = mn.group(1).strip()
            else:
                item.options.extend(o.strip() for o in p.split(",") if o.strip())
        order.items.append(item)
    return order
