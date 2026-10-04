FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="Plowshare A2A adapter" \
      org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /opt/plowshare
COPY plowshare-a2a/build/install/plowshare-a2a/lib/ lib/
COPY deploy/docker/a2a-entrypoint.sh /usr/local/bin/a2a-entrypoint
ENV PLOWSHARE_A2A_PORT=8093 JAVA_TOOL_OPTIONS="-Xms64m -Xmx512m -Djava.awt.headless=true"
USER 1000:1000
EXPOSE 8093
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD ["java", "-cp", "/opt/plowshare/lib/*", "io.aeyer.plowshare.a2a.HealthCheck"]
ENTRYPOINT ["/bin/sh", "/usr/local/bin/a2a-entrypoint"]
CMD ["/etc/plowshare/a2a.json"]
