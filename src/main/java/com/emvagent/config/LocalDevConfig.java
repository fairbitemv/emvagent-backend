package com.emvagent.config;

import com.google.cloud.bigquery.BigQuery;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Local dev profile: mock GCP BigQuery so app starts without GCP credentials.
 */
@Configuration
@Profile("local")
public class LocalDevConfig {

    @Bean
    public BigQuery bigQuery() {
        return Mockito.mock(BigQuery.class);
    }
}
