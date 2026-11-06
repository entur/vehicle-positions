FROM bellsoft/liberica-openjre-debian:27-36@sha256:0e0ed62763fd24cfd6db87e0f05e73a1cb18222ceed84d06161f396787d03036

RUN addgroup appuser && adduser --disabled-password appuser --ingroup appuser
USER appuser

WORKDIR /home/appuser

RUN chown -R appuser:appuser /home/appuser
USER appuser

ADD target/vehicle-positions-*-SNAPSHOT.jar vehicle-positions.jar

EXPOSE 8080 3000

# JDK-options are set in $JDK_JAVA_OPTIONS
CMD ["java", "-jar","vehicle-positions.jar"]