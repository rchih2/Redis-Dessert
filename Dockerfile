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

# 建立非 root 使用者（先建立，稍後在檔案都準備好、權限都設定好之後才切換）
RUN addgroup --system spring && adduser --system --ingroup spring spring

# 從 build 階段複製打包好的 jar
# pom.xml 已用 <finalName>app</finalName> 固定輸出檔名，這裡直接用固定路徑複製，避免萬用字元誤抓到錯誤的 jar
COPY --from=build /app/target/app.jar app.jar

# 切換成非 root 使用者執行，較安全
USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]