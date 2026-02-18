#!/bin/bash

# Xander Lab Backend - 阿里云镜像仓库部署脚本
# 功能：
#   1. 打包镜像
#   2. 上传到阿里云个人镜像仓库
#   3. 从阿里云拉取镜像并部署服务

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 默认配置
TAG="latest"
ENVIRONMENT="production"
STEP="all"
REGISTRY_URL="registry.cn-hangzhou.aliyuncs.com"
REGISTRY_NAMESPACE=""
IMAGE_NAME="xander-lab-backend"
CONTAINER_NAME="xander-lab-backend"
PORT=30002
NETWORK_NAME="xander-network"

# 解析命令行参数
show_help() {
  echo "使用方法: ./deploy-aliyun.sh [options]"
  echo "选项:"
  echo "  -t, --tag TAG           镜像版本标签 (默认: latest)"
  echo "  -e, --env ENV           部署环境 (默认: production)"
  echo "  -n, --namespace NS      命名空间 (阿里云镜像仓库命名空间)"
  echo "  -s, --step STEP         执行步骤: build|push|deploy|all (默认: all)"
  echo "  -h, --help              显示帮助信息"
}

while [[ $# -gt 0 ]]; do
  case $1 in
    -t|--tag) TAG="$2"; shift 2 ;;
    -e|--env) ENVIRONMENT="$2"; shift 2 ;;
    -n|--namespace) REGISTRY_NAMESPACE="$2"; shift 2 ;;
    -s|--step) STEP="$2"; shift 2 ;;
    -h|--help) show_help; exit 0 ;;
    *) echo -e "${RED}未知参数: $1${NC}"; show_help; exit 1 ;;
  esac
done

# 加载环境变量 (如果存在 .env.$ENVIRONMENT)
if [ -f ".env.$ENVIRONMENT" ]; then
  echo -e "${YELLOW}加载环境文件: .env.$ENVIRONMENT${NC}"
  export $(grep -v '^#' .env.$ENVIRONMENT | xargs)
fi

# 检查必要变量
REGISTRY_NAMESPACE=${REGISTRY_NAMESPACE:-$ALIYUN_REGISTRY_NAMESPACE}
if [ -z "$REGISTRY_NAMESPACE" ]; then
  echo -e "${RED}错误: 未指定命名空间，请通过 -n 参数或 ALIYUN_REGISTRY_NAMESPACE 环境变量设置${NC}"
  exit 1
fi

FULL_IMAGE_NAME="${REGISTRY_URL}/${REGISTRY_NAMESPACE}/${IMAGE_NAME}:${TAG}"

build_image() {
  echo -e "${BLUE}步骤 1: 构建 Docker 镜像${NC}"
  docker build -t "$IMAGE_NAME:$TAG" .
  docker tag "$IMAGE_NAME:$TAG" "$FULL_IMAGE_NAME"
  echo -e "${GREEN}✓ 镜像构建完成${NC}"
}

push_image() {
  echo -e "${BLUE}步骤 2: 推送镜像到阿里云${NC}"
  if [ -z "$ALIYUN_REGISTRY_USERNAME" ] || [ -z "$ALIYUN_REGISTRY_PASSWORD" ]; then
    echo -e "${RED}错误: 需要设置 ALIYUN_REGISTRY_USERNAME 和 ALIYUN_REGISTRY_PASSWORD${NC}"
    exit 1
  fi
  echo "$ALIYUN_REGISTRY_PASSWORD" | docker login "$REGISTRY_URL" -u "$ALIYUN_REGISTRY_USERNAME" --password-stdin
  docker push "$FULL_IMAGE_NAME"
  echo -e "${GREEN}✓ 镜像推送完成${NC}"
}

deploy_image() {
  echo -e "${BLUE}步骤 3: 部署服务${NC}"
  
  # 如果需要登录
  if [ -n "$ALIYUN_REGISTRY_USERNAME" ] && [ -n "$ALIYUN_REGISTRY_PASSWORD" ]; then
    echo "$ALIYUN_REGISTRY_PASSWORD" | docker login "$REGISTRY_URL" -u "$ALIYUN_REGISTRY_USERNAME" --password-stdin
  fi

  # 停止并删除旧容器
  docker stop "$CONTAINER_NAME" 2>/dev/null || true
  docker rm "$CONTAINER_NAME" 2>/dev/null || true

  # 拉取镜像
  docker pull "$FULL_IMAGE_NAME"

  # 启动容器
  echo -e "${YELLOW}启动容器...${NC}"
  docker run -d \
    --name "$CONTAINER_NAME" \
    --restart unless-stopped \
    -p "$PORT:$PORT" \
    --network "$NETWORK_NAME" \
    -e SPRING_PROFILES_ACTIVE="$ENVIRONMENT" \
    -e SPRING_DATASOURCE_URL="$SPRING_DATASOURCE_URL" \
    -e SPRING_DATASOURCE_USERNAME="$SPRING_DATASOURCE_USERNAME" \
    -e SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
    -e SPRING_MAIL_USERNAME="$SPRING_MAIL_USERNAME" \
    -e SPRING_MAIL_PASSWORD="$SPRING_MAIL_PASSWORD" \
    -e ALIYUN_OSS_ACCESS_KEY_ID="$ALIYUN_OSS_ACCESS_KEY_ID" \
    -e ALIYUN_OSS_ACCESS_KEY_SECRET="$ALIYUN_OSS_ACCESS_KEY_SECRET" \
    -e JWT_SECRET="$JWT_SECRET" \
    "$FULL_IMAGE_NAME"

  echo -e "${GREEN}✓ 服务部署完成${NC}"
}

case "$STEP" in
  build) build_image ;;
  push) push_image ;;
  deploy) deploy_image ;;
  all) build_image; push_image; deploy_image ;;
  *) echo -e "${RED}无效步骤: $STEP${NC}"; exit 1 ;;
esac
