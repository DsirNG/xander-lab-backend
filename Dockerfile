# Stage 1: Build the application
FROM maven:3.8.5-openjdk-17 AS builder

WORKDIR /app

# Copy pom.xml and settings.xml
COPY pom.xml .
COPY settings.xml .

# Download dependencies using Aliyun mirror
RUN mvn dependency:go-offline -B -s settings.xml

# Copy source code and build jar
COPY src ./src
RUN mvn package -DskipTests -s settings.xml


# Stage 2: Run the application
FROM openjdk:17-jdk-slim

WORKDIR /app

# img2threejs forge scripts are dependency-free but require a Python 3 runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

# Copy the built jar from the builder stage
COPY --from=builder /app/target/*.jar app.jar
# Keep the upstream pipeline in the runtime image; the backend executes these scripts by path.
COPY plugins/img2threejs ./plugins/img2threejs

# Spring Boot 运行在 30002
EXPOSE 30002

# 启动运行
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
