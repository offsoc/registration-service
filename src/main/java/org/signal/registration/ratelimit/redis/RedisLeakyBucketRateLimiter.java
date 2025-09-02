/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.redis;

import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.ratelimit.LeakyBucketRateLimiterConfiguration;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;

/**
 * A Redis token bucket rate limiter controls the rate at which actions may be taken using a
 * <a href="https://en.wikipedia.org/wiki/Leaky_bucket">leaky bucket</a> algorithm. Each key in a
 * {@code RedisLeakyBucketRateLimiter} maps to a distinct "bucket."
 *
 * @param <K> the type of key that identifies a token bucket for this rate limiter
 */
public abstract class RedisLeakyBucketRateLimiter<K> implements RateLimiter<K> {

  private final StatefulRedisConnection<String, String> connection;
  private final Clock clock;
  private final LeakyBucketRateLimiterConfiguration configuration;

  private final String script;
  private final String scriptHash;

  private final Counter failedOpenCounter;

  public RedisLeakyBucketRateLimiter(final StatefulRedisConnection<String, String> connection,
      final Clock clock,
      final LeakyBucketRateLimiterConfiguration configuration,
      final MeterRegistry meterRegistry) {

    this.connection = connection;
    this.clock = clock;
    this.configuration = configuration;

    this.failedOpenCounter = meterRegistry.counter(MetricsUtil.name(getClass(), "failedOpen"),
        "rateLimiterName", configuration.name());

    try (final InputStream inputStream = getClass().getResourceAsStream("validate-rate-limit.lua")) {
      if (inputStream == null) {
        throw new IOException("Rate limit validation script not found");
      }

      script = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
      scriptHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script.getBytes(StandardCharsets.UTF_8)));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError("All Java implementations must support SHA-1 as a MessageDigest algorithm");
    }
  }

  protected abstract String getBucketName(final K key);

  /**
   * Indicates whether this rate limiter should "fail open," or permit actions in the event of an error communicating
   * with the backing store.
   *
   * @return {@code true} if this rate limiter should allow actions to proceed in the event of an error communicating
   * with the backing store or {@code false} to treat communication failures as rate limit exceptions
   */
  protected abstract boolean shouldFailOpen();

  @Override
  public Optional<Instant> getTimeOfNextAction(final K key) {
    return Optional.of(clock.instant().plus(executeScript(getBucketName(key), false)));
  }

  @Override
  public void checkRateLimit(final K key) throws RateLimitExceededException {
    try {
      final Duration durationToNextAction = executeScript(getBucketName(key), true);

      if (durationToNextAction.isPositive()) {
        throw new RateLimitExceededException(durationToNextAction);
      }
    } catch (final RedisException e) {
      if (shouldFailOpen()) {
        failedOpenCounter.increment();
      } else {
        throw new RateLimitExceededException(null);
      }
    }
  }

  private Duration executeScript(final String key, final boolean consumeTokens) {
    final String[] keys = { key };

    final String[] arguments = {
        String.valueOf(configuration.maxCapacity()),
        String.valueOf(configuration.permitRegenerationPeriod().toMillis()),
        String.valueOf(configuration.minDelay().toMillis()),
        String.valueOf(clock.instant().toEpochMilli()),
        String.valueOf(consumeTokens)
    };

    try {
      return Duration.ofMillis(connection.sync().evalsha(scriptHash, ScriptOutputType.INTEGER, keys, arguments));
    } catch (final RedisNoScriptException e) {
      connection.sync().scriptLoad(script);
      return Duration.ofMillis(connection.sync().evalsha(scriptHash, ScriptOutputType.INTEGER, keys, arguments));
    }
  }
}
