# Build context contains only this Dockerfile and the matching wheelhouse.
# Resolve/pin the base image externally; no mutable production default.
ARG PYTHON_IMAGE
FROM ${PYTHON_IMAGE}
ARG SOURCE_REVISION
ARG SOURCE_DIGEST
LABEL org.opencontainers.image.title="Excalibur" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      io.plowshare.furrow.source-digest="${SOURCE_DIGEST}"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY wheels/ /tmp/wheels/
RUN python -m pip install --no-cache-dir --no-index --find-links=/tmp/wheels 'plowshare-agile[web]==0.1.0' \
    && rm -rf /tmp/wheels \
    && groupadd --gid 10001 furrow \
    && useradd --uid 10001 --gid furrow --no-create-home furrow
USER 10001:10001
ENTRYPOINT ["python", "-m", "plowshare_agile.container"]
