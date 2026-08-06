package com.ffroliva.tinyledger.cucumber;

import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Spec §9.3: the committed standalone acceptance subset, run in-process against the standalone
 * profile. Full-profile auth/admin acceptance is currently covered by JUnit integration tests; the
 * Python CLI and pytest-bdd stage that would bind the whole catalogue to a composed stack are planned
 * but not built (§9.6).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.ffroliva.tinyledger.cucumber")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@standalone")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary")
class CucumberTest {}
