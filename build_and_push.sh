# 个人脚本：后端一键打包部署到阿里云
# 运行环境：本地开发机 (Windows 或 Linux/Mac)

# 1. 设置配置 (根据您的阿里云信息修改)
ALIYUN_REGISTRY="crpi-v3gvy8meoymt6a59.cn-shenzhen.personal.cr.aliyuncs.com"
ALIYUN_NAMESPACE="test_xander"
IMAGE_NAME="api-xander"
TAG="v1.0.0" # 您可以每次修改这个版本号
USERNAME="aliyun6938781443"

# 2. 构建镜像 (在后端项目根目录执行)
echo "正在构建镜像..."
docker build -t $IMAGE_NAME:$TAG .

# 3. 登录并推送
echo "登录阿里云..."
# 提示：运行此脚本前建议先手动执行一次 docker login
docker tag $IMAGE_NAME:$TAG $ALIYUN_REGISTRY/$ALIYUN_NAMESPACE/$IMAGE_NAME:$TAG
docker push $ALIYUN_REGISTRY/$ALIYUN_NAMESPACE/$IMAGE_NAME:$TAG

echo "推送完成！镜像地址: $ALIYUN_REGISTRY/$ALIYUN_NAMESPACE/$IMAGE_NAME:$TAG"
