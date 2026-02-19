#!/bin/bash
# 服务器专用：一键构建、推送并在本地部署脚本
# 运行环境：已拉取代码的阿里云服务器 (Linux)

# --- 1. 配置信息 ---
REGISTRY="crpi-v3gvy8meoymt6a59.cn-shenzhen.personal.cr.aliyuncs.com"
NAMESPACE="test_xander"
IMAGE_NAME="api-xander"
CONTAINER_NAME="api-xander"
TAG="v1.0.0" 
USERNAME="aliyun6938781443"
FULL_IMAGE="$REGISTRY/$NAMESPACE/$IMAGE_NAME:$TAG"


echo "=========================================="
echo "   阿里云服务器：本地构建 + 推送 + 部署"
echo "=========================================="

# --- 2. 检查 .env.production 是否存在 ---
if [ ! -f ".env.production" ]; then
    echo "错误: 未找到 .env.production 文件！"
    echo "请先根据 .env.production.example 创建并填入数据库密码等配置。"
    exit 1
fi

# 加载环境变量
export $(grep -v '^#' .env.production | xargs)

# --- 3. 登录阿里云镜像仓库 (使用内网地址) ---
echo "正在登录阿里云镜像仓库 (内网)..."
if [ -z "$ALIYUN_REGISTRY_PASSWORD" ]; then
    echo "请输入您的阿里云镜像仓库密码/访问令牌:"
    read -s ALIYUN_REGISTRY_PASSWORD
fi
echo "$ALIYUN_REGISTRY_PASSWORD" | docker login "$REGISTRY" -u "$USERNAME" --password-stdin

# --- 4. 构建 Docker 镜像 ---
echo "正在本地构建镜像: $IMAGE_NAME:$TAG ..."
# 使用 Dockerfile 进行多阶段构建（包含 Maven 编译）
if ! docker build -t "$IMAGE_NAME:$TAG" .; then
    echo "=========================================="
    echo "错误: 镜像构建失败！"
    echo "提示: 如果卡在 Maven 下载，通常是网络问题。已为您添加阿里云镜像加速。"
    echo "=========================================="
    exit 1
fi

# --- 5. 标记镜像并推送至仓库 ---

echo "正在推送镜像至阿里云仓库..."
docker tag "$IMAGE_NAME:$TAG" "$FULL_IMAGE"
docker push "$FULL_IMAGE"

# --- 6. 停止并删除旧容器 ---
echo "正在清理旧容器..."
docker stop $CONTAINER_NAME 2>/dev/null || true
docker rm $CONTAINER_NAME 2>/dev/null || true

# --- 7. 启动新容器 ---
echo "正在启动新容器..."
docker run -d \
  --name $CONTAINER_NAME \
  --restart unless-stopped \
  -p 30002:30002 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL="$SPRING_DATASOURCE_URL" \
  -e SPRING_DATASOURCE_USERNAME="$SPRING_DATASOURCE_USERNAME" \
  -e SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  -e SPRING_MAIL_USERNAME="$SPRING_MAIL_USERNAME" \
  -e SPRING_MAIL_PASSWORD="$SPRING_MAIL_PASSWORD" \
  -e ALIYUN_OSS_ACCESS_KEY_ID="$ALIYUN_OSS_ACCESS_KEY_ID" \
  -e ALIYUN_OSS_ACCESS_KEY_SECRET="$ALIYUN_OSS_ACCESS_KEY_SECRET" \
  -e JWT_SECRET="$JWT_SECRET" \
  "$FULL_IMAGE"

echo "=========================================="
echo "✓ 全部流程完成！"
echo "容器状态:"
docker ps | grep $CONTAINER_NAME
echo "=========================================="
