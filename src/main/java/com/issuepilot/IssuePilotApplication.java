package com.issuepilot;

import com.issuepilot.config.EnvLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;

@SpringBootApplication(exclude = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
public class IssuePilotApplication {

    private static final Logger logger = LoggerFactory.getLogger(IssuePilotApplication.class);

    public static void main(String[] args) {
        // Load .env and atlas-credentials.env before Spring Boot initializes
        EnvLoader.loadEnv();
        logger.info("Starting Issue Pilot Spring Boot application...");
        SpringApplication.run(IssuePilotApplication.class, args);
    }
}
