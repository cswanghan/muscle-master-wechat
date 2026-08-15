FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
ENV SPRING_PROFILES_ACTIVE=dev
ENV WECHAT_MOCK=true
ENV SERVER_PORT=8080
COPY --from=build /src/target/muscle-master-server-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -Duser.timezone=Asia/Shanghai -jar /app/app.jar --server.port=${SERVER_PORT:-8080}"]
