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
COPY contracts/content-api/pom.xml ./contracts/content-api/pom.xml
COPY contracts/integration-api/pom.xml ./contracts/integration-api/pom.xml
COPY contracts/core-api/pom.xml ./contracts/core-api/pom.xml
COPY contracts/payment-api/pom.xml ./contracts/payment-api/pom.xml
COPY apps/core-host/pom.xml ./apps/core-host/pom.xml

# Download dependencies using Aliyun mirror
RUN mvn dependency:go-offline -B -s settings.xml

# Copy source code and build jar
COPY libs ./libs
COPY contracts ./contracts
COPY apps ./apps
RUN mvn package -DskipTests -s settings.xml


# Stage 2: Run the application
FROM openjdk:17-jdk-slim

WORKDIR /app

# img2threejs forge scripts are dependency-free but require a Python 3 runtime.
RUN sed -i \
        -e 's|http://deb.debian.org/debian|https://mirrors.cloud.tencent.com/debian|g' \
        -e 's|http://security.debian.org/debian-security|https://mirrors.cloud.tencent.com/debian-security|g' \
        /etc/apt/sources.list \
    && apt-get -o Acquire::Retries=5 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=5 -o Acquire::https::Timeout=30 install -y --no-install-recommends \
        curl \
        python3 \
    && rm -rf /var/lib/apt/lists/*

# Copy the built jar from the builder stage (the application module is apps/core-host)
COPY --from=builder /app/apps/core-host/target/*.jar app.jar
# Keep the upstream pipeline in the runtime image; the backend executes these scripts by path.
COPY plugins/img2threejs ./plugins/img2threejs

# Spring Boot 运行在 30002
EXPOSE 30002

# 启动运行
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
