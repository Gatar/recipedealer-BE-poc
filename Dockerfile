# Full JDK (not JRE) on purpose: jcmd, jstack, jfr etc. are available for diagnostics on the server.
FROM eclipse-temurin:25.0.4.1_1-jdk-noble

RUN groupadd --system --gid 10001 recipedealer \
    && useradd --system --uid 10001 --gid recipedealer --home-dir /app --shell /usr/sbin/nologin recipedealer \
    && mkdir -p /app /data/blobs \
    && chown -R recipedealer:recipedealer /app /data

WORKDIR /app
COPY --chown=recipedealer:recipedealer recipedealer.jar /app/recipedealer.jar

USER recipedealer
EXPOSE 8080
ENV JAVA_OPTS="-Xmx1g -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/recipedealer.jar"]
