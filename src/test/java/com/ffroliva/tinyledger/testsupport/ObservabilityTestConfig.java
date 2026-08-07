package com.ffroliva.tinyledger.testsupport;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * §9.4's {@link InMemorySpanExporter}, contributed to the <em>one</em> shared {@code full} context.
 *
 * <p><strong>Imported on {@link AbstractIntegrationTest} itself, never on a subclass.</strong> ADR 0003
 * §1 and {@code AGENTS.md} trap 5: an {@code @Import} on a subclass forks the context by definition, and
 * CR13 — a second {@code AuditKafkaListener} joining the same consumer group and taking partitions from
 * {@code KafkaAuditModuleIT} — was that fork's symptom. Declared on the base it moves the cache key
 * uniformly, so every IT still shares one context and nothing forks.
 *
 * <p>Boot collects {@code SpanExporter} beans and wraps them in a batch processor. That processor's
 * schedule delay is lowered through {@code AbstractIntegrationTest}'s property source rather than by
 * substituting a {@code SimpleSpanProcessor} here: registering a processor <em>and</em> an exporter would
 * export every span twice, and a duplicated span reads as a tracing defect rather than as a test-wiring
 * mistake.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ObservabilityTestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }
}
