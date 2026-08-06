package com.ffroliva.tinyledger.platform;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import java.util.function.Supplier;

/**
 * Spec §6.1: where a bucket's state lives is the one thing that differs by run mode — local JVM
 * memory in {@code standalone}, Redis in {@code full}, so limits are shared across instances. The
 * bucket math itself (capacity, refill, whether a request is over the limit) is Bucket4j's own
 * {@link Bucket}, identical in both modes; only its resolution is behind this seam.
 *
 * <p>Kept behind an interface, same as {@code BalanceCachePort} (§4.5), so {@link RateLimitFilter}
 * — the class that decides which bucket applies and whether it is exhausted — is unit-testable
 * with {@link LocalRateLimiterStore} alone and needs no Redis container.
 */
public interface RateLimiterStore {
    Bucket resolveBucket(String key, Supplier<BucketConfiguration> configuration);
}
