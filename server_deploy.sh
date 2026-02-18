#!/bin/bash
# 服务器部署脚本：server_deploy.sh
# 运行环境：阿里云服务器 (Linux)

# --- 1. 配置信息 (已根据您的信息填入) ---
REGISTRY="crpi-v3gvy8meoymt6a59-vpc.cn-shenzhen.personal.cr.aliyuncs.com" # 使用 VPC 内网地址，速度更快
NAMESPACE="test_xander"
IMAGE_NAME="api-xander"
CONTAINER_NAME="api-xander"
TAG="v1.0.0" # 保持与推送的版本号一致
FULL_IMAGE="$REGISTRY/$NAMESPACE/$IMAGE_NAME:$TAG"

# --- 2. 只有第一次需要登录 (可以手动执行一次，或者脚本里执行) ---
# docker login --username=aliyun6938781443 $REGISTRY

# --- 3. 停止并删除旧容器 ---
echo "停止容器: $CONTAINER_NAME..."
docker stop $CONTAINER_NAME 2>/dev/null || true
docker rm $CONTAINER_NAME 2>/dev/null || true

# --- 4. 拉取最新镜像 ---
echo "从内网拉取镜像: $FULL_IMAGE..."
docker pull $FULL_IMAGE

# --- 5. 启动容器 (在这里注入您的敏感配置) ---
# 注意：请将下面的 your_xxx 替换为实际值，或者在服务器创建 .env 文件
echo "启动新容器..."
docker run -d \
  --name $CONTAINER_NAME \
  --restart unless-stopped \
  -p 30002:30002 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://您的数据库地址:3306/xander_lab?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=GMT%2B8" \
  -e SPRING_DATASOURCE_USERNAME="root" \
  -e SPRING_DATASOURCE_PASSWORD="您的密码" \
  -e SPRING_MAIL_USERNAME="您的邮箱" \
  -e SPRING_MAIL_PASSWORD="您的授权码" \
  -e ALIYUN_OSS_ACCESS_KEY_ID="您的ID" \
  -e ALIYUN_OSS_ACCESS_KEY_SECRET="您的Secret" \
  -e JWT_SECRET="您的JWT密钥" \
  $FULL_IMAGE

echo "部署成功！"
docker ps | grep $CONTAINER_NAME
