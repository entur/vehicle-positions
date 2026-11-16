FROM bellsoft/liberica-openjre-debian:25.0.5.0.3-5@sha256:d5f6c00def204e1b7c6147342be2291243e586ac41b97b62ab6ba0eb6dff5ad3

# Nothing in the container uses curl (probes are httpGet, preStop only runs sleep), and it carries
# most of the base image's critical CVEs. dpkg --purge fails the build if another package depends
# on curl or libcurl4, rather than silently removing that package too. autoremove then drops their
# no longer needed dependencies (e.g. libsasl2), and upgrade picks up Debian security fixes.
RUN dpkg --purge curl libcurl4 \
    && apt-get update \
    && apt-get autoremove --purge -y \
    && apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/*

RUN addgroup appuser && adduser --disabled-password appuser --ingroup appuser
USER appuser

WORKDIR /home/appuser

RUN chown -R appuser:appuser /home/appuser
USER appuser

ADD target/vehicle-positions-*-SNAPSHOT.jar vehicle-positions.jar

EXPOSE 8080 3000

# JDK-options are set in $JDK_JAVA_OPTIONS
CMD ["java", "-jar","vehicle-positions.jar"]