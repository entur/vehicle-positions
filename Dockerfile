FROM openjdk:11-jre
ADD target/vehicle-positions-*-SNAPSHOT.jar vehicle-positions.jar

EXPOSE 8080 3000
CMD java $JAVA_OPTIONS -jar /vehicle-positions.jar