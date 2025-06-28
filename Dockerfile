# Use Maven to build the project
FROM maven:3.9.6-eclipse-temurin-21 as builder

# Set working directory
WORKDIR /app

# Copy source code to build stage
COPY . .

# Build the JAR inside the container
RUN mvn clean package -DskipTests

# --- Stage 2: Create lightweight image to run the app ---
FROM eclipse-temurin:17-jdk-jammy

# Copy only the JAR from the builder image
COPY --from=builder /app/target/*.jar app.jar

# Run the Spring Boot app
ENTRYPOINT ["java", "-jar", "/app.jar"]
