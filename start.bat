@echo off
chcp 65001 >nul
echo ========================================
echo   Spring AI RAG 文档问答系统
echo ========================================
echo.

echo [1/4] 检查 Docker 是否运行...
docker version >nul 2>&1
if errorlevel 1 (
    echo ❌ Docker 未运行，请先启动 Docker Desktop
    pause
    exit /b 1
)
echo ✅ Docker 已运行

echo.
echo [2/4] 启动 PGVector 数据库...
docker-compose up -d
if errorlevel 1 (
    echo ❌ 数据库启动失败
    pause
    exit /b 1
)

echo.
echo [3/4] 等待数据库就绪...
timeout /t 8 /nobreak >nul
docker-compose ps

echo.
echo [4/4] 检查 Ollama 服务...
curl -s http://localhost:11434/api/tags >nul 2>&1
if errorlevel 1 (
    echo.
    echo ⚠️  警告：Ollama 服务未运行
    echo.
    echo 请确保：
    echo 1. 已安装 Ollama：https://ollama.ai/download
    echo 2. 已拉取模型：
    echo    ollama pull llama3.2
    echo    ollama pull nomic-embed-text
    echo.
    echo 按任意键继续启动应用（如果 Ollama 已就绪）
    pause
) else (
    echo ✅ Ollama 服务正常
)

echo.
echo ========================================
echo   正在启动应用...
echo   访问地址：http://localhost:8080
echo   按 Ctrl+C 停止应用
echo ========================================
echo.

mvnw.cmd spring-boot:run
