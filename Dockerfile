FROM bellsoft/liberica-openjre-debian:25.0.5.0.3-5@sha256:d5f6c00def204e1b7c6147342be2291243e586ac41b97b62ab6ba0eb6dff5ad3

RUN addgroup appuser && adduser --disabled-password appuser --ingroup appuser
USER appuser

WORKDIR /home/appuser

RUN chown -R appuser:appuser /home/appuser
USER appuser

ADD target/vehicle-positions-*-SNAPSHOT.jar vehicle-positions.jar

EXPOSE 8080 3000

# JDK-options are set in $JDK_JAVA_OPTIONS
CMD ["java", "-jar","vehicle-positions.jar"]