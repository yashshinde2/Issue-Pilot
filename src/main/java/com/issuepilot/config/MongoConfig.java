package com.issuepilot.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.lang.NonNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Configuration
public class MongoConfig {

    private static final Logger logger = LoggerFactory.getLogger(MongoConfig.class);

    private final AppProperties properties;
    private MongoClient mongoClient;
    private MongoTemplate mongoTemplate;
    private boolean connected = false;

    public MongoConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    public MongoClient mongoClient() {
        if (this.mongoClient != null) {
            return this.mongoClient;
        }

        String rawUri = properties.getStagingMongodbUri();
        if (rawUri == null || rawUri.isBlank()) {
            rawUri = properties.getMongodbUri();
        }
        String maskedUri = properties.getSanitizedMongodbUri();
        logger.info("Initializing MongoDB Atlas connection to {}", maskedUri);

        try {
            if (rawUri == null || rawUri.isBlank() || (!rawUri.startsWith("mongodb://") && !rawUri.startsWith("mongodb+srv://"))) {
                rawUri = "mongodb://localhost:27017";
            }
            ConnectionString connectionString = new ConnectionString(rawUri);
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .applyToConnectionPoolSettings(builder -> builder
                            .maxSize(properties.getMongodbMaxPoolSize())
                            .minSize(properties.getMongodbMinPoolSize())
                            .maxConnectionIdleTime(properties.getMongodbMaxIdleTimeMs(), TimeUnit.MILLISECONDS)
                    )
                    .applyToSocketSettings(builder -> builder
                            .connectTimeout(properties.getMongodbConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                            .readTimeout(properties.getMongodbSocketTimeoutMs(), TimeUnit.MILLISECONDS)
                    )
                    .applyToClusterSettings(builder -> builder
                            .serverSelectionTimeout(properties.getMongodbServerSelectionTimeoutMs(), TimeUnit.MILLISECONDS)
                    )
                    .retryWrites(true)
                    .retryReads(true)
                    .build();

            this.mongoClient = MongoClients.create(settings);
            // Verify connectivity via admin ping
            try {
                this.mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
                this.connected = true;
                logger.info("Successfully connected to MongoDB Atlas database '{}'", getEffectiveDatabaseName());
            } catch (Exception pingEx) {
                logger.warn("Initial MongoDB ping failed: {}. Application will run in degraded/sample mode.", pingEx.getMessage());
                this.connected = false;
            }
        } catch (Exception e) {
            logger.error("Failed to construct MongoDB client: {}", e.getMessage());
            this.connected = false;
        }

        return this.mongoClient;
    }

    @NonNull
    public String getEffectiveDatabaseName() {
        String rawUri = properties.getStagingMongodbUri();
        if (rawUri == null || rawUri.isBlank()) {
            rawUri = properties.getMongodbUri();
        }
        if (rawUri != null && !rawUri.isBlank()) {
            try {
                ConnectionString cs = new ConnectionString(rawUri);
                String db = cs.getDatabase();
                if (db != null && !db.isBlank()) {
                    return Objects.requireNonNull(db);
                }
            } catch (Exception ignored) {}
        }
        String configuredDb = properties.getMongodbDatabase();
        if (configuredDb != null && !configuredDb.isBlank()) {
            return Objects.requireNonNull(configuredDb);
        }
        return "issue_pilot";
    }

    @Bean
    public MongoTemplate mongoTemplate() {
        if (this.mongoTemplate != null) {
            return this.mongoTemplate;
        }
        MongoClient client = mongoClient();
        if (client != null) {
            String dbName = getEffectiveDatabaseName();
            this.mongoTemplate = new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, dbName));
        }
        return this.mongoTemplate;
    }

    public boolean isConnected() {
        if (!connected || mongoClient == null) {
            return false;
        }
        try {
            mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PostConstruct
    public void initIndexes() {
        if (!isConnected()) {
            logger.warn("Skipping index initialization because MongoDB is not connected.");
            return;
        }

        try {
            MongoDatabase db = mongoClient.getDatabase(getEffectiveDatabaseName());

            // 1. issues collection indexes
            var issues = db.getCollection("issues");
            issues.createIndex(
                    Indexes.ascending("source", "repository_full_name", "issue_number"),
                    new IndexOptions().name("idx_source_repo_issue").unique(true)
            );
            issues.createIndex(
                    Indexes.ascending("id"),
                    new IndexOptions().name("idx_issue_id").unique(true).sparse(true)
            );
            issues.createIndex(
                    Indexes.ascending("source", "source_instance", "source_issue_id"),
                    new IndexOptions().name("idx_source_instance_issue_id").sparse(true)
            );
            issues.createIndex(
                    Indexes.ascending("state", "is_assigned"),
                    new IndexOptions().name("idx_state_assigned")
            );
            issues.createIndex(
                    Indexes.ascending("detected_technologies"),
                    new IndexOptions().name("idx_detected_technologies")
            );
            issues.createIndex(
                    Indexes.descending("updated_at"),
                    new IndexOptions().name("idx_updated_at")
            );
            issues.createIndex(
                    Indexes.ascending("stale_status"),
                    new IndexOptions().name("idx_stale_status")
            );

            // 2. users collection indexes
            var users = db.getCollection("users");
            users.createIndex(
                    Indexes.ascending("user_id"),
                    new IndexOptions().name("idx_user_id").unique(true)
            );
            users.createIndex(
                    Indexes.descending("updated_at"),
                    new IndexOptions().name("idx_user_updated_at")
            );

            // 3. recommendations collection indexes
            var recs = db.getCollection("recommendations");
            recs.createIndex(
                    Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("generated_at")),
                    new IndexOptions().name("idx_user_generated")
            );
            recs.createIndex(
                    Indexes.ascending("issue_id"),
                    new IndexOptions().name("idx_rec_issue_id")
            );
            recs.createIndex(
                    Indexes.descending("generated_at"),
                    new IndexOptions().name("idx_generated_at")
            );
            recs.createIndex(
                    Indexes.ascending("run_id", "issue_id"),
                    new IndexOptions().name("idx_rec_run_issue")
            );

            // 4. feedback collection indexes
            var feedback = db.getCollection("feedback");
            feedback.createIndex(
                    Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("event_timestamp")),
                    new IndexOptions().name("idx_feedback_user")
            );
            feedback.createIndex(
                    Indexes.ascending("issue_id"),
                    new IndexOptions().name("idx_feedback_issue")
            );
            feedback.createIndex(
                    Indexes.ascending("event_type"),
                    new IndexOptions().name("idx_feedback_event_type")
            );

            // 5. jobs collection indexes
            var jobs = db.getCollection("jobs");
            jobs.createIndex(
                    Indexes.ascending("job_id"),
                    new IndexOptions().name("idx_job_id").unique(true)
            );
            jobs.createIndex(
                    Indexes.descending("start_time"),
                    new IndexOptions().name("idx_job_start")
            );
            jobs.createIndex(
                    Indexes.ascending("status"),
                    new IndexOptions().name("idx_job_status")
            );

            logger.info("MongoDB Atlas indexes for all 5 collections verified and ready.");
        } catch (Exception e) {
            logger.error("Failed to initialize MongoDB indexes: {}", e.getMessage());
        }
    }
}
