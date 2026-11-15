@echo off
rem Product Image Studio: SDXL on the local GPU with a browser control panel (no Docker needed).
cd /d "%~dp0"
if not exist ".venv-images\Scripts\python.exe" (
  echo .venv-images is missing - see the README section "Product images".
  pause
  exit /b 1
)
if not exist "output\products.jsonl" (
  echo output\products.jsonl is missing. Start the scratch DB once and run:
  echo   .venv-images\Scripts\python.exe tools\export_products.py
  pause
  exit /b 1
)
".venv-images\Scripts\python.exe" tools\imagegen_server.py %*
pause
