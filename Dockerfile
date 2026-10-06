FROM gradle:8.10-jdk17 AS build
WORKDIR /src
COPY . .
RUN cd RuntimeBridges/Desktop && gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/RuntimeBridges/Desktop/build/libs/desktop_bridge*.jar /app/desktop_bridge.jar
RUN mkdir -p /app/cs-extensions
COPY extensions/ /app/cs-extensions/
RUN mkdir -p /root/Documents/AnymeX/ExtensionSettings && \
    echo '{"movielinkbd_main_url":"https://y2mk7d.movielinkbd.li"}' > /root/Documents/AnymeX/ExtensionSettings/com.lagradost.cloudstream3.json
ENV CS_EXTENSIONS_DIR="/app/cs-extensions"
ENV CS_ALLOWED_SOURCE_IDS="cs_debflixtest,cs_rtally,cs_movielinkbd,cs_cinetv,cs_castletvusevlc,cs_moviesmod,cs_topmovies"
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx384m -XX:+UseSerialGC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses=true"
EXPOSE 8080
CMD ["java","-cp","/app/desktop_bridge.jar","com.anymex.desktop.RailwayHealthServerKt"]
