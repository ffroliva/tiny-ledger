package com.ffroliva.tinyledger.platform;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.local.LocalBucketBuilder;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Spec §6.1: {@code standalone}'s bucket storage — local, in-memory, and deliberately bounded and
 * expiring, the detail the spec calls out by name: "unauthenticated traffic cannot grow memory
 * without bound." A plain {@code ConcurrentHashMap} keyed by IP would do exactly that under a
 * flood of distinct source addresses; Caffeine evicts the least-recently-used key once
 * {@code maximumSize} is reached and expires an idle key outright after {@code expireAfterAccess}
 * — either bound alone caps memory, both together is cheap insurance. Applied uniformly to every
 * key this store resolves (principal and IP alike) rather than special-cased per key shape: one
 * mechanism, not two.
 */
public class LocalRateLimiterStore implements RateLimiterStore {

    private final Cache<String, Bucket> buckets;

    public LocalRateLimiterStore() {
        this.buckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    @Override
    public Bucket resolveBucket(String key, Supplier<BucketConfiguration> configuration) {
        return buckets.get(key, k -> toLocalBucket(configuration.get()));
    }

    private static Bucket toLocalBucket(BucketConfiguration configuration) {
        LocalBucketBuilder builder = Bucket.builder();
        for (Bandwidth bandwidth : configuration.getBandwidths()) {
            builder.addLimit(bandwidth);
        }
        return builder.build();
    }
}
