@echo off
setlocal
set "ROOT=%~dp0.."
set "VENV=%ROOT%\rex-uninlu-service\.venv"

if not exist "%VENV%\Scripts\python.exe" (
  echo [rex] creating virtual environment...
  python -m venv "%VENV%" || exit /b 1
)

echo [rex] installing CPU torch...
"%VENV%\Scripts\python.exe" -m pip install torch==2.2.2 --index-url https://download.pytorch.org/whl/cpu || exit /b 1

echo [rex] installing sidecar dependencies...
"%VENV%\Scripts\python.exe" -m pip install -r "%ROOT%\rex-uninlu-service\requirements-dev.txt" || exit /b 1

echo [rex] downloading model...
"%VENV%\Scripts\python.exe" "%ROOT%\scripts\download-rex-uninlu.py" || exit /b 1

echo [rex] setup complete.
endlocal
