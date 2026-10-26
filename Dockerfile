# Full JDK (not JRE) on purpose: jcmd, jstack, jfr etc. are available for diagnostics on the server.
FROM eclipse-temurin:25.0.4.1_1-jdk-noble

# pg_dump for on-request backups (POST /v1/admin/backups); same major and minor version as the postgres image.
# Ubuntu ships only PostgreSQL 16, so the client comes from the PostgreSQL apt repository (PGDG).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt noble-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-18=18.6-1.pgdg24.04+2 \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 recipedealer \
    && useradd --system --uid 10001 --gid recipedealer --home-dir /app --shell /usr/sbin/nologin recipedealer \
    && mkdir -p /app /data/blobs /data/backups \
    && chown -R recipedealer:recipedealer /app /data

WORKDIR /app
COPY --chown=recipedealer:recipedealer recipedealer.jar /app/recipedealer.jar

USER recipedealer
EXPOSE 8080
ENV JAVA_OPTS="-Xmx1g -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/recipedealer.jar"]
