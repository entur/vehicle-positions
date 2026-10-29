FROM eclipse-temurin:25-jre-jammy@sha256:7d1b8bf8b361b932f8c76589b963bc164e4fff416d832169f8dfe26c8aae8f50

RUN addgroup appuser && adduser --disabled-password appuser --ingroup appuser
USER appuser

WORKDIR /home/appuser

RUN chown -R appuser:appuser /home/appuser
USER appuser

ADD target/vehicle-positions-*-SNAPSHOT.jar vehicle-positions.jar

EXPOSE 8080 3000

# JDK-options are set in $JDK_JAVA_OPTIONS
CMD ["java", "-jar","vehicle-positions.jar"]