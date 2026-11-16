@echo off
cd /d %~dp0
if not exist .venv (
  py -3 -m venv .venv
  .venv\Scripts\pip install -r requirements.txt pywin32
)
if not exist config.json copy config.example.json config.json
.venv\Scripts\python -m motngum
pause
