# ===== 阶段 1：构建 =====
# 只复制 pom.xml 先拉依赖，利用 Docker 层缓存（改代码不必重下依赖）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# 跳过测试：单测/集成测试需要外部 MySQL，在 CI 里单独跑（见 M2-6）
RUN mvn -q -B package -DskipTests

# ===== 阶段 2：运行 =====
FROM eclipse-temurin:21-jre

# ⚠️ 必须装字体：项目的登录验证码走 Hutool ShearCaptcha → AWT/BufferedImage，
# 而 temurin JRE 默认无 fontconfig + 无字体文件，会报
# "Fontconfig head is null" 或 "Could not initialize class sun.font.SunFontManager"
# （见 docs/DESIGN.md §6.3「容器内验证码无字体」）
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
 && fc-cache -f \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 28089

# 时区与编码（中文环境）
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

# 健康检查：目前只能反映“进程活着”。
# ⚠️ 已知局限（claude 检查 M2）：本项目任何异常都会渲染成 HTTP 200 的错误页
#    （M1/M2 反复验证过：DB 失联时首页仍 200），所以这条 HEALTHCHECK
#    不能反映“依赖可用”。待 M3 新增 /api/health（检查 DB + Redis）后改打该端点。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://127.0.0.1:28089/ >/dev/null || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
