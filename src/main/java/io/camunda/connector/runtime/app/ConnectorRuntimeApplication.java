package io.camunda.connector.runtime.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ConnectorRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConnectorRuntimeApplication.class, args);
    }
}
