FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
ARG SERVER_JAR=plowshare-server/build/libs/plowshare-server-0.1.0-SNAPSHOT.jar
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="Plowshare server" \
      org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /opt/plowshare
COPY ${SERVER_JAR} server.jar
COPY deploy/docker/server-entrypoint.sh /usr/local/bin/server-entrypoint
COPY deploy/docker/server-filestores.sh /usr/local/bin/server-filestores.sh
COPY deploy/docker/server-healthcheck.sh /usr/local/bin/server-healthcheck
ENV PLOWSHARE_BIND=0.0.0.0 PLOWSHARE_PORT=8091 \
    PLOWSHARE_DATA_DIR=/var/lib/plowshare \
    PLOWSHARE_AUTH_TOKEN_FILE=/var/lib/plowshare/console-token \
    SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/etc/plowshare/ \
    JAVA_TOOL_OPTIONS="-Xms256m -Xmx3g -Djava.awt.headless=true"
USER 1000:1000
EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD ["/bin/bash", "/usr/local/bin/server-healthcheck"]
ENTRYPOINT ["/bin/sh", "/usr/local/bin/server-entrypoint"]
