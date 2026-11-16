"""Vẽ tem dán ly thành ảnh đen trắng (1-bit).

Vẽ thành ảnh rồi gửi dạng BITMAP cho máy in giúp in được tiếng Việt có dấu
(font có sẵn trong máy in tem thường không có dấu tiếng Việt).
"""
from __future__ import annotations

import datetime as dt
import os
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFont

FONT_CANDIDATES = [
    # Linux
    "/usr/share/fonts/truetype/dejavu/DejaVuSans{b}.ttf",
    "/usr/share/fonts/truetype/noto/NotoSans-{B}.ttf",
    # Windows
    "C:/Windows/Fonts/arial{bw}.ttf",
    "C:/Windows/Fonts/tahoma{bw}.ttf",
    # macOS
    "/System/Library/Fonts/Supplemental/Arial{bm}.ttf",
    "/Library/Fonts/Arial{bm}.ttf",
]


@lru_cache(maxsize=4)
def _font_path(bold: bool, override: str = "") -> str | None:
    if override and os.path.exists(override):
        return override
    here = os.path.join(os.path.dirname(__file__), "fonts", "Label-Bold.ttf" if bold else "Label-Regular.ttf")
    if os.path.exists(here):
        return here
    for c in FONT_CANDIDATES:
        p = c.format(
            b="-Bold" if bold else "",
            B="Bold" if bold else "Regular",
            bw="bd" if bold else "",
            bm=" Bold" if bold else "",
        )
        if os.path.exists(p):
            return p
    return None


@lru_cache(maxsize=64)
def _font(size: int, bold: bool, override: str = "") -> ImageFont.ImageFont:
    p = _font_path(bold, override)
    if p:
        return ImageFont.truetype(p, size)
    return ImageFont.load_default(size)


def _wrap(draw: ImageDraw.ImageDraw, text: str, font, width: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    cur = ""
    for w in words:
        trial = f"{cur} {w}".strip()
        if draw.textlength(trial, font=font) <= width:
            cur = trial
            continue
        if cur:
            lines.append(cur)
        # Từ quá dài: cắt theo ký tự
        while draw.textlength(w, font=font) > width and len(w) > 1:
            cut = len(w)
            while cut > 1 and draw.textlength(w[:cut], font=font) > width:
                cut -= 1
            lines.append(w[:cut])
            w = w[cut:]
        cur = w
    if cur:
        lines.append(cur)
    return lines


def _fit(draw, text, bold, override, width, max_lines, start, minimum):
    """Chọn cỡ chữ lớn nhất để text vừa trong max_lines dòng."""
    size = start
    while size > minimum:
        f = _font(size, bold, override)
        lines = _wrap(draw, text, f, width)
        if len(lines) <= max_lines:
            return f, lines
        size -= 1
    f = _font(minimum, bold, override)
    lines = _wrap(draw, text, f, width)
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = lines[-1].rstrip()[:-1] + "…"
    return f, lines


def label_size_px(label_cfg: dict) -> tuple[int, int]:
    dpi = label_cfg.get("dpi", 203)
    w = round(label_cfg.get("width_mm", 50) / 25.4 * dpi)
    h = round(label_cfg.get("height_mm", 30) / 25.4 * dpi)
    return w - w % 8, h


def render_label(
    order: dict,
    item: dict,
    index: int,
    total: int,
    label_cfg: dict,
    shop_name: str = "",
    source_label: str = "",
    now: dt.datetime | None = None,
) -> Image.Image:
    """Vẽ 1 tem cho 1 ly. index bắt đầu từ 1."""
    W, H = label_size_px(label_cfg)
    dpi = label_cfg.get("dpi", 203)
    s = dpi / 203.0  # hệ số phóng theo độ phân giải
    margin = round(label_cfg.get("margin_mm", 1.5) / 25.4 * dpi)
    fr, fb = label_cfg.get("font_path", ""), label_cfg.get("font_bold_path", "")

    img = Image.new("L", (W, H), 255)
    d = ImageDraw.Draw(img)
    x0, x1 = margin, W - margin
    width = x1 - x0
    y = margin

    # --- Dòng đầu: mã đơn + số thứ tự ly (nền đen chữ trắng) ---
    head_font = _font(round(24 * s), True, fb)
    code = order.get("code") or f"#{order.get('id', '')}"
    left = f"{source_label} {code}".strip()
    right = f"{index}/{total}"
    head_h = round(30 * s)
    d.rectangle([x0, y, x1, y + head_h], fill=0)
    ty = y + (head_h - head_font.size) // 2 - round(2 * s)
    d.text((x0 + round(4 * s), ty), left, font=head_font, fill=255)
    d.text((x1 - round(4 * s) - d.textlength(right, font=head_font), ty), right, font=head_font, fill=255)
    y += head_h + round(4 * s)

    # --- Footer ---
    now = now or dt.datetime.now()
    footer_tpl = label_cfg.get("footer", "{time} · {shop}")
    footer = footer_tpl.format(time=now.strftime("%H:%M %d/%m"), shop=shop_name, customer=order.get("customer", ""))
    foot_font = _font(round(16 * s), False, fr)
    foot_y = H - margin - foot_font.size - round(2 * s)

    # --- Tên món ---
    name = item.get("name", "")
    if label_cfg.get("show_price") and item.get("price"):
        name = f"{name} ({item['price']})"
    name_font, name_lines = _fit(d, name, True, fb, width, 2, round(30 * s), round(18 * s))
    for ln in name_lines:
        d.text((x0, y), ln, font=name_font, fill=0)
        y += name_font.size + round(3 * s)

    # --- Tuỳ chọn + ghi chú: co chữ cho vừa chỗ còn lại ---
    detail_parts = []
    if item.get("options"):
        detail_parts.append(", ".join(item["options"]))
    notes = [n for n in (item.get("note"), order.get("note")) if n]
    if notes:
        detail_parts.append("* " + " / ".join(notes))
    avail = foot_y - y - round(2 * s)
    if detail_parts and avail > 10:
        size = round(20 * s)
        while True:
            f = _font(size, False, fr)
            lh = f.size + round(2 * s)
            lines = [ln for part in detail_parts for ln in _wrap(d, part, f, width)]
            if len(lines) * lh <= avail or size <= round(13 * s):
                break
            size -= 1
        max_lines = max(1, avail // lh)
        if len(lines) > max_lines:
            lines = lines[:max_lines]
            lines[-1] = lines[-1][:-1] + "…"
        for ln in lines:
            d.text((x0, y), ln, font=f, fill=0)
            y += lh

    # --- vẽ footer ---
    d.line([x0, foot_y - round(2 * s), x1, foot_y - round(2 * s)], fill=0, width=1)
    d.text((x0, foot_y), footer, font=foot_font, fill=0)

    return img.point(lambda p: 255 if p > 140 else 0).convert("1", dither=Image.Dither.NONE)


def expand_cups(order: dict) -> list[dict]:
    """Mỗi ly 1 tem: món số lượng 3 -> 3 tem."""
    cups = []
    for it in order.get("items", []):
        for _ in range(max(1, int(it.get("qty", 1)))):
            cups.append(it)
    return cups


def render_order_labels(order: dict, cfg: dict, source_label: str = "", only: list[int] | None = None) -> list[Image.Image]:
    cups = expand_cups(order)
    total = len(cups)
    out = []
    for i, item in enumerate(cups, start=1):
        if only and i not in only:
            continue
        out.append(render_label(order, item, i, total, cfg["label"], cfg.get("shop_name", ""), source_label))
    return out
