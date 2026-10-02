# Throwaway image for the injection calibration corpus.
#
# Used only by scripts/run-injection-corpus.sh. The corpus holds adversarial samples, so
# it is never scored on a developer or production host. This image exists so that work
# happens in a disposable, capability-free container instead.
#
# SHADOW ONLY: the probe hardcodes InjectionGate.Mode.SHADOW, so nothing in the corpus can
# be withheld or forwarded regardless of what the config file says.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependency layer first so source edits do not invalidate the dependency cache.
COPY pom.xml .
COPY rahu-core/pom.xml rahu-core/
COPY rahu-openrouter/pom.xml rahu-openrouter/
COPY rahu-systemone/pom.xml rahu-systemone/
COPY rahu-cli/pom.xml rahu-cli/
RUN mvn -q -B dependency:go-offline -DskipTests || true

COPY . .
RUN mvn -q -B -DskipTests package

# Runtime stage: Maven plus a JRE is required, because the probe is launched with
# exec:java (the shaded jar is not standalone). Source, shell history, and the corpus are
# all absent from the image - the corpus is mounted read-only at run time so the image
# never carries adversarial content.
FROM maven:3.9-eclipse-temurin-21
WORKDIR /corpus
COPY --from=build /src/rahu-cli/target/classes /corpus/classes
COPY --from=build /src/rahu-cli/target/*.jar /corpus/rahu-cli.jar
COPY --from=build /src/rahu-core/target/classes /corpus/core-classes
COPY --from=build /src/rahu-systemone/target/classes /corpus/systemone-classes
COPY --from=build /src/rahu-openrouter/target/classes /corpus/openrouter-classes

# The corpus and its config are mounted at run time, read-only, by the run script.
# Placeholder so the paths exist and a bare `docker run` cannot pick up a corpus from the
# build context.
RUN printf '%s\n' '{"note":"corpus mounted at run time; see scripts/run-injection-corpus.sh"}' \
      > /corpus/config.json

# Wrapper so the probe is PID 1 with an explicit classpath. Hardcodes SHADOW-only
# behaviour via the probe itself; args are the corpus path and the config path.
COPY scripts/injection-corpus-entrypoint.sh /corpus/entrypoint.sh
RUN chmod +x /corpus/entrypoint.sh
ENTRYPOINT ["/corpus/entrypoint.sh"]