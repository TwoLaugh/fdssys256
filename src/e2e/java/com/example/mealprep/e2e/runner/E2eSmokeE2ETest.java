package com.example.mealprep.e2e.runner;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * JUnit-Platform suite launcher for the Cucumber E2E harness.
 *
 * <p>Named {@code *E2ETest} so the {@code e2e}-profile Failsafe execution (see {@code pom.xml}
 * {@code <profiles>}) picks it up. It is <b>not</b> a {@code @SpringBootTest}: it launches
 * Cucumber, whose step definitions drive the <i>already-running</i> docker-compose app over real
 * HTTP via REST-assured. Nothing here boots a Spring context.
 *
 * <p>This class compiles only under {@code -Pe2e} (the {@code src/e2e/java} source dir is
 * registered by build-helper solely in that profile), so a plain {@code mvn verify} never sees it.
 *
 * <p>Feature selection + glue + reporters live in {@code
 * src/e2e/resources/junit-platform.properties}; the two {@code @ConfigurationParameter}s below are
 * belt-and-braces so the suite is self-describing even if that file is moved.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.example.mealprep.e2e.steps")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, html:target/cucumber-report.html, json:target/cucumber-report.json")
public class E2eSmokeE2ETest {}
