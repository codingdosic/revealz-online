@echo off
setlocal
cd /d "%~dp0.."
if not exist ".env" (
  echo Missing repository-root .env. Copy .env.example and fill secrets first.
  exit /b 1
)
docker volume inspect revealz_pgdata >nul 2>&1 || docker volume create revealz_pgdata >nul
docker compose up -d postgres redis lobby
if errorlevel 1 exit /b 1
echo Spring backend started. Check http://127.0.0.1:8080/v1/health
