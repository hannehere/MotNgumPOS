"""Chuyển ảnh tem thành lệnh máy in (TSPL / ESC-POS) và gửi tới máy in.

- TSPL: máy in tem nhiệt Xprinter (XP-365B, XP-360B, XP-420B, XP-470B...), TSC, Gprinter, HPRT...
- ESC/POS: máy in hoá đơn 58/80mm (dùng giấy decal cuộn) — in xong cắt giấy.

Cách kết nối (transport):
- dryrun : không in, lưu file .bin + .png vào data/prints để kiểm tra
- tcp    : máy in LAN/WiFi, cổng 9100 (raw)
- device : ghi thẳng ra thiết bị, ví dụ /dev/usb/lp0 (Linux) hoặc cổng COM
- cups   : Linux/macOS qua CUPS: lp -d <tên> -o raw
- windows: máy in đã cài driver trên Windows (cần pywin32)
"""
from __future__ import annotations

import socket
import subprocess
import time
from pathlib import Path

from PIL import Image, ImageOps


# ---------------------------------------------------------------- TSPL -----
def tspl_job(images: list[Image.Image], label_cfg: dict, density: int = 8, speed: int = 4) -> bytes:
    w, h, gap = label_cfg.get("width_mm", 50), label_cfg.get("height_mm", 30), label_cfg.get("gap_mm", 2)
    out = bytearray()
    out += (
        f"SIZE {w} mm,{h} mm\r\nGAP {gap} mm,0 mm\r\nDENSITY {density}\r\nSPEED {speed}\r\n"
        "DIRECTION 1,0\r\nREFERENCE 0,0\r\nSET TEAR ON\r\n"
    ).encode()
    for img in images:
        bw = img.convert("1")
        if bw.width % 8:
            pad = Image.new("1", (bw.width + 8 - bw.width % 8, bw.height), 1)
            pad.paste(bw, (0, 0))
            bw = pad
        # TSPL: bit 1 = trắng, bit 0 = in đen -> trùng với ảnh mode "1" của Pillow
        data = bw.tobytes()
        out += b"CLS\r\n"
        out += f"BITMAP 0,0,{bw.width // 8},{bw.height},0,".encode() + data + b"\r\n"
        out += b"PRINT 1,1\r\n"
    return bytes(out)


# -------------------------------------------------------------- ESC/POS ----
def escpos_job(images: list[Image.Image], cut: bool = True) -> bytes:
    out = bytearray(b"\x1b@")  # init
    for img in images:
        bw = img.convert("1")
        if bw.width % 8:
            pad = Image.new("1", (bw.width + 8 - bw.width % 8, bw.height), 1)
            pad.paste(bw, (0, 0))
            bw = pad
        # ESC/POS raster: bit 1 = in đen -> đảo màu
        data = ImageOps.invert(bw.convert("L")).convert("1").tobytes()
        wb, h = bw.width // 8, bw.height
        out += b"\x1dv0\x00" + bytes([wb & 0xFF, wb >> 8, h & 0xFF, h >> 8]) + data
        out += b"\x1bd\x03"  # feed 3 dòng
        if cut:
            out += b"\x1dV\x42\x00"  # partial cut
    return bytes(out)


# ------------------------------------------------------------ transport ----
class PrinterError(RuntimeError):
    pass


def send(data: bytes, pcfg: dict, data_dir: str = "data", images: list[Image.Image] | None = None) -> str:
    t = pcfg.get("transport", "dryrun")
    try:
        if t == "tcp":
            with socket.create_connection((pcfg["host"], int(pcfg.get("port", 9100))), timeout=5) as s:
                s.sendall(data)
            return f"tcp://{pcfg['host']}:{pcfg.get('port', 9100)}"
        if t == "device":
            with open(pcfg["device"], "wb") as f:
                f.write(data)
            return pcfg["device"]
        if t == "cups":
            subprocess.run(["lp", "-d", pcfg["cups_name"], "-o", "raw"], input=data, check=True, timeout=15)
            return f"cups:{pcfg['cups_name']}"
        if t == "windows":
            import win32print  # type: ignore

            h = win32print.OpenPrinter(pcfg["windows_name"])
            try:
                win32print.StartDocPrinter(h, 1, ("MotNgum label", None, "RAW"))
                win32print.StartPagePrinter(h)
                win32print.WritePrinter(h, data)
                win32print.EndPagePrinter(h)
                win32print.EndDocPrinter(h)
            finally:
                win32print.ClosePrinter(h)
            return f"windows:{pcfg['windows_name']}"
        if t == "dryrun":
            d = Path(data_dir) / "prints"
            d.mkdir(parents=True, exist_ok=True)
            stamp = time.strftime("%Y%m%d-%H%M%S") + f"-{int(time.time() * 1000) % 1000:03d}"
            (d / f"{stamp}.bin").write_bytes(data)
            for i, img in enumerate(images or [], 1):
                img.save(d / f"{stamp}-{i}.png")
            return str(d / f"{stamp}.bin")
    except PrinterError:
        raise
    except Exception as e:  # noqa: BLE001
        raise PrinterError(f"Không in được qua '{t}': {e}") from e
    raise PrinterError(f"transport không hợp lệ: {t}")


def print_images(images: list[Image.Image], cfg: dict) -> str:
    if not images:
        raise PrinterError("Không có tem nào để in")
    pcfg = cfg["printer"]
    if pcfg.get("language", "tspl") == "escpos":
        data = escpos_job(images, cut=pcfg.get("escpos_cut", True))
    else:
        data = tspl_job(images, cfg["label"], pcfg.get("density", 8), pcfg.get("speed", 4))
    return send(data, pcfg, cfg.get("data_dir", "data"), images)
