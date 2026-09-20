# Stage 1: Build the application
FROM maven:3.8.5-openjdk-17 AS builder

WORKDIR /app

# Copy the POMs first so the dependency layer is cached independently of source edits.
# Every module in the root POM's <modules> must appear here, or the reactor fails before
# it compiles anything ("Child module ... does not exist"). Adding a module means editing
# this file twice: once for its pom.xml here, once for its sources below.
COPY pom.xml .
COPY settings.xml .
COPY libs/xander-model/pom.xml ./libs/xander-model/pom.xml
COPY libs/xander-mybatis-support/pom.xml ./libs/xander-mybatis-support/pom.xml
COPY libs/xander-auth-core/pom.xml ./libs/xander-auth-core/pom.xml
COPY libs/xander-observability-core/pom.xml ./libs/xander-observability-core/pom.xml
COPY libs/xander-test-support/pom.xml ./libs/xander-test-support/pom.xml
COPY libs/xander-webmvc-support/pom.xml ./libs/xander-webmvc-support/pom.xml
COPY contracts/content-api/pom.xml ./contracts/content-api/pom.xml
COPY contracts/integration-api/pom.xml ./contracts/integration-api/pom.xml
COPY contracts/core-api/pom.xml ./contracts/core-api/pom.xml
COPY contracts/payment-api/pom.xml ./contracts/payment-api/pom.xml
COPY contracts/agent-api/pom.xml ./contracts/agent-api/pom.xml
COPY contracts/automation-api/pom.xml ./contracts/automation-api/pom.xml
COPY contracts/recitation-api/pom.xml ./contracts/recitation-api/pom.xml
COPY contracts/blogagent-api/pom.xml ./contracts/blogagent-api/pom.xml
COPY apps/bloghtml-preview/pom.xml ./apps/bloghtml-preview/pom.xml
COPY apps/gateway/pom.xml ./apps/gateway/pom.xml
COPY apps/core-host/pom.xml ./apps/core-host/pom.xml
COPY apps/migration-runner/pom.xml ./apps/migration-runner/pom.xml

# Download dependencies using Aliyun mirror
RUN mvn dependency:go-offline -B -s settings.xml

# Copy source code and build jar
COPY libs ./libs
COPY contracts ./contracts
COPY apps ./apps
COPY docs ./docs
COPY migrations ./migrations
RUN mvn clean package -DskipTests -s settings.xml


# Stage 2: Run the application
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# img2threejs forge scripts are dependency-free but require a Python 3 runtime.
RUN sed -i \
        -e 's|http://archive.ubuntu.com/ubuntu|https://mirrors.cloud.tencent.com/ubuntu|g' \
        -e 's|http://security.ubuntu.com/ubuntu|https://mirrors.cloud.tencent.com/ubuntu|g' \
        /etc/apt/sources.list \
    && apt-get -o Acquire::Retries=5 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=5 -o Acquire::https::Timeout=30 install -y --no-install-recommends \
        curl \
        python3 \
    && rm -rf /var/lib/apt/lists/*

# The default remains the legacy-compatible core-host image. Wave 1 deployments select
# another executable with --build-arg APP_MODULE=apps/gateway (or bloghtml-preview /
# migration-runner) without changing the dependency/build graph above.
ARG APP_MODULE=apps/core-host
ARG APP_PORT=30002
COPY --from=builder /app/${APP_MODULE}/target/*.jar app.jar
# Keep the upstream pipeline in the runtime image; the backend executes these scripts by path.
COPY plugins/img2threejs ./plugins/img2threejs

# Spring Boot port is selected together with APP_MODULE.
EXPOSE ${APP_PORT}

# 启动运行
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
