package com.flaviooliva.ledger.cucumber;

import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Spec §9.3: the acceptance catalogue, run in-process against the standalone profile. Only the
 * {@code @standalone} subset runs here — {@code @full} rows need auth, Kafka and real Postgres, and
 * pytest-bdd re-runs the whole catalogue against the composed stack (§9.6).
 *
 * <p>{@code @known-gap} excludes scenarios that specify behaviour the ledger does not yet honour. They are
 * written, executable and red on purpose; drop the tag from the scenario when the production fix lands and
 * it starts running with no other change. Removing this clause from the filter runs them all — which is how
 * you check the gaps are still gaps.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.flaviooliva.ledger.cucumber")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@standalone and not @known-gap")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary")
class CucumberTest {}
