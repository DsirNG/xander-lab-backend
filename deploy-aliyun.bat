@echo off
setlocal enabledelayedexpansion

:: Xander Lab Backend - 阿里云镜像仓库部署脚本 (Windows)

:: 默认配置
set TAG=latest
set ENVIRONMENT=production
set STEP=all
set REGISTRY_URL=registry.cn-hangzhou.aliyuncs.com
set IMAGE_NAME=xander-lab-backend
set CONTAINER_NAME=xander-lab-backend
set PORT=30002
set NETWORK_NAME=xander-network

:parse_args
if "%~1"=="" goto check_env
if "%~1"=="-t" set TAG=%~2& shift & shift & goto parse_args
if "%~1"=="--tag" set TAG=%~2& shift & shift & goto parse_args
if "%~1"=="-e" set ENVIRONMENT=%~2& shift & shift & goto parse_args
if "%~1"=="--env" set ENVIRONMENT=%~2& shift & shift & goto parse_args
if "%~1"=="-n" set REGISTRY_NAMESPACE=%~2& shift & shift & goto parse_args
if "%~1"=="--namespace" set REGISTRY_NAMESPACE=%~2& shift & shift & goto parse_args
if "%~1"=="-s" set STEP=%~2& shift & shift & goto parse_args
if "%~1"=="--step" set STEP=%~2& shift & shift & goto parse_args
if "%~1"=="-h" goto show_help
if "%~1"=="--help" goto show_help
echo Unknown argument: %1
goto show_help

:show_help
echo Usage: deploy-aliyun.bat [options]
echo Options:
echo   -t, --tag TAG           Mirror version tag (default: latest)
echo   -e, --env ENV           Deployment environment (default: production)
echo   -n, --namespace NS      Namespace (Aliyun registry namespace)
echo   -s, --step STEP         Step: build|push|deploy|all (default: all)
exit /b 0

:check_env
:: 加载环境文件
if exist .env.%ENVIRONMENT% (
    echo Loading environment file: .env.%ENVIRONMENT%
    for /f "tokens=*" %%a in ('type .env.%ENVIRONMENT% ^| findstr /v "^#"') do (
        set %%a
    )
)

if "%REGISTRY_NAMESPACE%"=="" set REGISTRY_NAMESPACE=%ALIYUN_REGISTRY_NAMESPACE%
if "%REGISTRY_NAMESPACE%"=="" (
    echo Error: Namespace not specified. Use -n or set ALIYUN_REGISTRY_NAMESPACE env var.
    exit /b 1
)

set FULL_IMAGE_NAME=%REGISTRY_URL%/%REGISTRY_NAMESPACE%/%IMAGE_NAME%:%TAG%

if "%STEP%"=="build" goto build
if "%STEP%"=="push" goto push
if "%STEP%"=="deploy" goto deploy
if "%STEP%"=="all" goto build

:build
echo Step 1: Building Docker image...
docker build -t %IMAGE_NAME%:%TAG% .
docker tag %IMAGE_NAME%:%TAG% %FULL_IMAGE_NAME%
echo Mirror build completed.
if "%STEP%"=="build" exit /b 0

:push
echo Step 2: Pushing mirror to Aliyun...
if "%ALIYUN_REGISTRY_USERNAME%"=="" (
    echo Error: ALIYUN_REGISTRY_USERNAME not set.
    exit /b 1
)
echo %ALIYUN_REGISTRY_PASSWORD% | docker login %REGISTRY_URL% -u %ALIYUN_REGISTRY_USERNAME% --password-stdin
docker push %FULL_IMAGE_NAME%
echo Mirror push completed.
if "%STEP%"=="push" exit /b 0

:deploy
echo Step 3: Deploying service...
docker stop %CONTAINER_NAME% 2>nul
docker rm %CONTAINER_NAME% 2>nul
docker pull %FULL_IMAGE_NAME%
docker network create %NETWORK_NAME% 2>nul

echo Starting container...
docker run -d ^
  --name %CONTAINER_NAME% ^
  --restart unless-stopped ^
  -p %PORT%:%PORT% ^
  --network %NETWORK_NAME% ^
  -e SPRING_PROFILES_ACTIVE=%ENVIRONMENT% ^
  -e SPRING_DATASOURCE_URL="%SPRING_DATASOURCE_URL%" ^
  -e SPRING_DATASOURCE_USERNAME="%SPRING_DATASOURCE_USERNAME%" ^
  -e SPRING_DATASOURCE_PASSWORD="%SPRING_DATASOURCE_PASSWORD%" ^
  -e SPRING_MAIL_USERNAME="%SPRING_MAIL_USERNAME%" ^
  -e SPRING_MAIL_PASSWORD="%SPRING_MAIL_PASSWORD%" ^
  -e ALIYUN_OSS_ACCESS_KEY_ID="%ALIYUN_OSS_ACCESS_KEY_ID%" ^
  -e ALIYUN_OSS_ACCESS_KEY_SECRET="%ALIYUN_OSS_ACCESS_KEY_SECRET%" ^
  -e JWT_SECRET="%JWT_SECRET%" ^
  %FULL_IMAGE_NAME%

echo Service deployment completed.
exit /b 0
