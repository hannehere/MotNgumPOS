#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -r requirements.txt; }
[ -f config.json ] || cp config.example.json config.json
exec .venv/bin/python -m motngum "$@"
