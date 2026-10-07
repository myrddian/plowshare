FROM node:22-bookworm-slim AS node-runtime
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update -qq && apt-get install -y -qq bubblewrap gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node-runtime /usr/local/bin/node /usr/bin/node
# Disposable process-isolation fixture with Java 21 and supported Node 22; no database.
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.title="Plowshare isolation acceptance" \
    org.opencontainers.image.revision=$SOURCE_REVISION
WORKDIR /source
