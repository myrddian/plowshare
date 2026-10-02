# The search adapter is independent of the SearXNG engine it connects to.
FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
ARG SEARCH_PROVIDER_JAR=extensions/search-searxng/build/libs/search-searxng-0.1.0-SNAPSHOT.jar
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="Plowshare SearXNG adapter" \
      org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /opt/plowshare
COPY ${SEARCH_PROVIDER_JAR} search-provider.jar
COPY deploy/docker/search-provider-entrypoint.sh /usr/local/bin/search-provider-entrypoint
ENV SEARXNG_BIND=0.0.0.0 SEARXNG_PORT=8086 JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m"
USER 1000:1000
EXPOSE 8086
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD bash -ec 'exec 3<>/dev/tcp/127.0.0.1/${SEARXNG_PORT:-8086}; printf "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; read -r protocol status rest <&3; test "$status" = 200'
ENTRYPOINT ["/bin/sh", "/usr/local/bin/search-provider-entrypoint"]
