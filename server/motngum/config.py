"""Đọc cấu hình từ config.json (hoặc biến môi trường MOTNGUM_CONFIG)."""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path
from typing import Any

DEFAULTS: dict[str, Any] = {
    "shop_name": "Một Ngụm",
    "api_key": "doi-ma-nay-di",
    "db_path": "data/motngum.db",
    "data_dir": "data",
    "auto_print": True,
    "print_delay_seconds": 4,
    "order_code_reuse_hours": 12,
    "printer": {
        "language": "tspl",  # tspl (máy in tem Xprinter/TSC/Gprinter) | escpos (máy in bill)
        "transport": "dryrun",  # dryrun | tcp | device | cups | windows
        "host": "192.168.1.100",
        "port": 9100,
        "device": "/dev/usb/lp0",
        "cups_name": "Xprinter",
        "windows_name": "Xprinter XP-365B",
        "density": 8,
        "speed": 4,
        "escpos_cut": True,
    },
    "label": {
        "width_mm": 50,
        "height_mm": 30,
        "gap_mm": 2,
        "dpi": 203,
        "margin_mm": 1.5,
        "font_path": "",
        "font_bold_path": "",
        "show_price": False,
        "footer": "{time} · {shop}",
    },
    "sources": {
        "com.grab.merchant": {
            "name": "grab",
            "label": "GRAB",
            "code_patterns": [r"\bGF-?\d{2,6}\b", r"\bGM-?\d{2,6}\b"],
        }
    },
}

GENERIC_CODE_PATTERNS = [r"#\s?([A-Z0-9][A-Z0-9-]{2,})", r"\b[A-Z]{2,3}-\d{2,6}\b"]


def _merge(base: dict, override: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict) and k != "sources":
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def load_config(path: str | os.PathLike | None = None) -> dict[str, Any]:
    path = path or os.environ.get("MOTNGUM_CONFIG") or "config.json"
    p = Path(path)
    cfg = copy.deepcopy(DEFAULTS)
    if p.exists():
        with p.open(encoding="utf-8") as f:
            cfg = _merge(cfg, json.load(f))
    if os.environ.get("MOTNGUM_API_KEY"):
        cfg["api_key"] = os.environ["MOTNGUM_API_KEY"]
    return cfg


def source_for_package(cfg: dict, package: str | None) -> dict[str, Any]:
    """Trả về thông tin nguồn đơn (grab/shopee/...) theo package name của app Android."""
    if package and package in cfg.get("sources", {}):
        src = dict(cfg["sources"][package])
    else:
        name = (package or "manual").split(".")[-1] if package else "manual"
        src = {"name": name, "label": name.upper()[:8]}
    src.setdefault("code_patterns", GENERIC_CODE_PATTERNS)
    src.setdefault("label", src["name"].upper())
    return src
