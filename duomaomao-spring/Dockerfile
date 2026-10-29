# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build
# 在容器里从源码构建。适合 CI 或「VPS 能上网」的场景。
# 只想搬产物时用 Dockerfile.dist，不需要 Maven。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 先只拷 pom，让依赖层单独缓存；go-offline 失败不影响后续（只是少一层缓存）
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:17-jre-alpine

# busybox 自带 wget，用来做 HEALTHCHECK；不需要额外装 curl
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build /src/target/duomaomao-api-server-0.1.0-exec.jar /app/app.jar

# 非 root 运行
USER app
EXPOSE 8080

# 日志一律走 stdout，交给 docker logs / 采集器
ENV PORT=8080 \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -XX:+ExitOnOutOfMemoryError"

# 20 MB 堆起步的机器也能跑；真实上限看容器内存与 MaxRAMPercentage
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1

# application.yml 里配了 server.shutdown=graceful + 20s，给 SIGTERM 留出收尾时间
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
