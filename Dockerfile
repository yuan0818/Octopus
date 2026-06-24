FROM eclipse-temurin:21-jre
WORKDIR /opt/octopus

COPY jvm-project-analyzer/build/libs/jvm-project-analyzer-*.jar /opt/octopus/jvm-project-analyzer.jar
COPY code-intelligence/build/libs/code-intelligence-*.jar /opt/octopus/octopus.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms512m", "-Xmx4g", "-jar", "/opt/octopus/octopus.jar"]
