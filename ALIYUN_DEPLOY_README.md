# 阿里云镜像仓库部署指南 - 后端

本指南介绍如何像前端一样，使用阿里云容器镜像服务进行后端的镜像构建、推送和本地/远程部署。

## 📋 前置要求

1. **Docker** 已安装并运行
2. **阿里云容器镜像服务** 账号和命名空间
3. 已在本地创建 `.env.production` 文件（参考 `.env.production.example`）

## 🔧 配置说明

### 1. 环境变量配置

复制 `.env.production.example` 为 `.env.production`，并填入您的真实敏感配置（数据库密码、密钥等）。此文件已被 `.gitignore` 忽略，安全可靠。

```bash
cp .env.production.example .env.production
```

### 2. 核心参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `ALIYUN_REGISTRY_URL` | 阿里云镜像仓库地址 | `registry.cn-hangzhou.aliyuncs.com` |
| `ALIYUN_REGISTRY_NAMESPACE` | 您的命名空间 | (必填) |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | (必填) |

## 🚀 使用方法

### Linux/Mac

```bash
# 给予执行权限
chmod +x deploy-aliyun.sh

# 完整流程：构建 + 推送 + 部署
./deploy-aliyun.sh -n your-namespace -t v1.0.0

# 仅部署（在服务器上拉取最新镜像并启动）
./deploy-aliyun.sh -n your-namespace -s deploy
```

### Windows

```cmd
:: 完整流程
deploy-aliyun.bat -n your-namespace -t v1.0.0

:: 仅部署
deploy-aliyun.bat -n your-namespace -s deploy
```

## 🔄 部署逻辑

1. **Build**: 使用 `Dockerfile` 进行多阶段构建（Maven 编译 + JRE 运行）。
2. **Push**: 将构建好的镜像打上阿里云仓库标签并推送。
3. **Deploy**: 
   - 自动停止并删除同名容器。
   - 拉取最新镜像。
   - **核心点**：通过 `docker run -e` 将 `.env.production` 中的敏感配置注入到 Spring Boot 容器中，从而实现代码中无明文密码。

## 📝 常用命令

- **查看日志**: `docker logs -f xander-lab-backend`
- **检查状态**: `docker ps`
- **进入容器**: `docker exec -it xander-lab-backend /bin/sh`
