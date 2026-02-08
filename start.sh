#!/bin/bash

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================"
echo "  Spring AI RAG 文档问答系统"
echo "========================================"
echo ""

# 检查 Docker
echo "[1/4] 检查 Docker 是否运行..."
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}❌ Docker 未运行，请先启动 Docker${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Docker 已运行${NC}"

# 启动数据库
echo ""
echo "[2/4] 启动 PGVector 数据库..."
docker-compose up -d
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 数据库启动失败${NC}"
    exit 1
fi

# 等待数据库就绪
echo ""
echo "[3/4] 等待数据库就绪..."
sleep 8
docker-compose ps

# 检查 Ollama
echo ""
echo "[4/4] 检查 Ollama 服务..."
if ! curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo ""
    echo -e "${YELLOW}⚠️  警告：Ollama 服务未运行${NC}"
    echo ""
    echo "请确保："
    echo "1. 已安装 Ollama：https://ollama.ai/download"
    echo "2. 已拉取模型："
    echo "   ollama pull llama3.2"
    echo "   ollama pull nomic-embed-text"
    echo ""
    read -p "按回车键继续启动应用（如果 Ollama 已就绪）..."
else
    echo -e "${GREEN}✅ Ollama 服务正常${NC}"
fi

# 启动应用
echo ""
echo "========================================"
echo "  正在启动应用..."
echo "  访问地址：http://localhost:8080"
echo "  按 Ctrl+C 停止应用"
echo "========================================"
echo ""

./mvnw spring-boot:run

#赋予执行权限：
#bash
#chmod +x start.sh
