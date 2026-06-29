@echo off
chcp 65001 >nul
title PaddleOCR API 服务 (端口 8866)

set "BASE_DIR=%~dp0"
set "VENV_PYTHON=%BASE_DIR%paddle2_env\Scripts\python.exe"

echo.
echo ╔══════════════════════════════════════════════╗
echo ║   PaddleOCR API - 干部人事档案识别服务       ║
echo ╚══════════════════════════════════════════════╝
echo.
echo   项目目录: %BASE_DIR%
echo   Python:   %VENV_PYTHON%
echo   端口:     8866
echo   引擎:     PaddleOCR PP-OCRv4
echo.
echo   接口:
echo     GET  http://localhost:8866/health
echo     POST http://localhost:8866/ocr
echo     POST http://localhost:8866/ocr-batch
echo.

if not exist "%VENV_PYTHON%" (
    echo   [错误] paddle2_env 虚拟环境不存在！
    echo   请在 C:\Users\ju\Desktop\干部人事管理 下创建 paddle2_env
    pause
    exit /b 1
)

echo   正在启动服务...
echo   ═══════════════════════════════════════════════
echo.
"%VENV_PYTHON%" "%BASE_DIR%paddle_server.py"

pause
