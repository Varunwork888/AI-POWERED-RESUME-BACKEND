# Use a base Java image
FROM openjdk:17-jdk-slim

# Copy your JAR file into the image
COPY target/*.jar app.jar

# Set the command to run the jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
