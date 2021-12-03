FROM openjdk:11-jdk

RUN addgroup appuser && adduser --disabled-password appuser --ingroup appuser
USER appuser

WORKDIR /home/appuser

RUN chown -R appuser:appuser /home/appuser
USER appuser

ADD target/vehicle-positions-*-SNAPSHOT.jar vehicle-positions.jar

EXPOSE 8080 3000

# JDK-options are set in $JDK_JAVA_OPTIONS
CMD ["java", "-jar","vehicle-positions.jar"]