package com.emvagent.config;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Prod profile: create real BigQuery client using Application Default Credentials (ADC).
 * On Cloud Run the service account provides ADC automatically.
 */
@Configuration
@Profile("prod")
public class ProdGcpConfig {

    private static final Logger log = LoggerFactory.getLogger(ProdGcpConfig.class);

    @Value("${gcp.bigquery.project:emvagent-ai-dev}")
    private String gcpProject;

    @Bean
    public BigQuery bigQuery() {
        try {
            BigQuery bq = BigQueryOptions.newBuilder()
                    .setProjectId(gcpProject)
                    .build()
                    .getService();
            log.info("BigQuery client initialised for project={}", gcpProject);
            return bq;
        } catch (Exception e) {
            log.error("Failed to initialise BigQuery client: {}", e.getMessage());
            return null;
        }
    }
}
