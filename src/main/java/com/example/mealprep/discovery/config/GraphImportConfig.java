package com.example.mealprep.discovery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link GraphImportProperties} (G06/G11 graph-batch ingest flags). */
@Configuration
@EnableConfigurationProperties(GraphImportProperties.class)
public class GraphImportConfig {}
