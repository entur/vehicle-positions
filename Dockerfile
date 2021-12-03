FROM openjdk:11-jdk

RUN addgroup appuser && adduser --disabled-password appuser --ingroup appuser
USER appuser

WORKDIR /home/appuser

RUN chown -R appuser:appuser /home/appuser
USER appuser

ADD target/vehicle-positions-*-SNAPSHOT.jar vehicle-positions.jar

EXPOSE 8080 3000
# CMD java $JAVA_OPTIONS -jar vehicle-positions.jar

CMD ["java","-server","-Xmx2G","-XX:+UseParallelGC","-XX:GCTimeRatio=4","-XX:AdaptiveSizePolicyWeight=90","-XX:MinHeapFreeRatio=20","-XX:MaxHeapFreeRatio=40","-Dspring.config.additional-location=/etc/application-config/application.properties","-Dfile.encoding=UTF-8","-jar","vehicle-positions.jar"]