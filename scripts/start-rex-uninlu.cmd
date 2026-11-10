@echo off
setlocal
set "ROOT=%~dp0.."
set "VENV=%ROOT%\rex-uninlu-service\.venv"

if not exist "%VENV%\Scripts\python.exe" (
  echo [rex] virtual environment not found. Run scripts\setup-rex-uninlu.cmd first.
  exit /b 1
)

if not defined REX_UNINLU_MODEL_DIR set "REX_UNINLU_MODEL_DIR=%ROOT%\rex-uninlu-service\models\iic\nlp_deberta_rex-uninlu-chinese-base"
if not defined REX_UNINLU_PORT set "REX_UNINLU_PORT=8108"
if not defined REX_UNINLU_HOST set "REX_UNINLU_HOST=127.0.0.1"
set "PYTHONPATH=%ROOT%\rex-uninlu-service;%PYTHONPATH%"
set "TRANSFORMERS_OFFLINE=1"
set "HF_HUB_OFFLINE=1"

echo [rex] starting sidecar on %REX_UNINLU_HOST%:%REX_UNINLU_PORT%
cd /d "%ROOT%\rex-uninlu-service"
"%VENV%\Scripts\python.exe" -m uvicorn app.main:app --host %REX_UNINLU_HOST% --port %REX_UNINLU_PORT% --workers 1 --log-level info
endlocal
