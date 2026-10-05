FROM gradle:8.10-jdk17 AS build
WORKDIR /src
COPY . .
RUN cd RuntimeBridges/Desktop && chmod +x gradlew && ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/RuntimeBridges/Desktop/build/libs/desktop_bridge*.jar /app/desktop_bridge.jar
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx384m -XX:+UseSerialGC"
EXPOSE 8080
CMD ["java","-cp","/app/desktop_bridge.jar","com.anymex.desktop.RailwayHealthServerKt"]
