FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the JAR with dependencies and BouncyCastle libs
COPY target/connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar .
COPY target/lib/bcprov-jdk15on-1.70.jar ./lib/
COPY target/lib/bcpkix-jdk15on-1.70.jar ./lib/
COPY target/lib/bcutil-jdk15on-1.70.jar ./lib/

EXPOSE 8089

# Run the connector runtime application
CMD ["java", "-cp", "connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar", \
     "io.camunda.connector.runtime.app.ConnectorRuntimeApplication", \
     "--server.port=8089", \
     "--zeebe.client.broker.gateway-address=orchestration:26500", \
     "--zeebe.client.security.plaintext=true"]
