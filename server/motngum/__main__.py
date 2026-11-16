"""Chạy: python -m motngum  (mặc định cổng 8686, mở cho cả mạng LAN)."""
import argparse
import logging

import uvicorn

from .app import create_app
from .config import load_config


def main() -> None:
    ap = argparse.ArgumentParser(description="MotNgum POS server")
    ap.add_argument("--config", default=None, help="đường dẫn config.json")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8686)
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config(args.config)
    if cfg["api_key"] == "doi-ma-nay-di":
        logging.warning("Đang dùng API key mặc định — hãy đổi 'api_key' trong config.json")
    uvicorn.run(create_app(cfg), host=args.host, port=args.port)


if __name__ == "__main__":
    main()
