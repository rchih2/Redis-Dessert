# ===== Stage 1: 建置 (Build) =====
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# 先只複製 pom.xml 並下載相依套件，可利用 Docker layer cache，
# 之後只改程式碼時不用重新下載整包 Maven 依賴
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 複製原始碼並打包（略過測試以加快建置速度，正式 CI 建議拿掉 -DskipTests）
COPY src ./src
RUN mvn clean package -DskipTests -B

# ===== Stage 2: 執行 (Runtime) =====
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN addgroup --system spring && adduser --system --ingroup spring spring

COPY --from=build /app/target/app.jar app.jar

USER spring:spring

EXPOSE 8080

ENV TZ=Asia/Taipei

ENTRYPOINT ["java", "-Duser.timezone=Asia/Taipei", "-jar", "/app/app.jar"]