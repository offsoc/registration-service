/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.redis;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import org.signal.registration.ratelimit.LeakyBucketRateLimiterConfiguration;

@Singleton
@Requires(bean = StatefulRedisConnection.class)
@Named("send-sms-verification-code-per-number")
public class RedisLeakyBucketSendSmsVerificationCodeByNumberRateLimiter extends RedisLeakyBucketRateLimiter<Phonenumber.PhoneNumber> {

  public RedisLeakyBucketSendSmsVerificationCodeByNumberRateLimiter(final StatefulRedisConnection<String, String> connection,
                                                                    final Clock clock,
                                                                    @Named("send-sms-verification-code-per-number") final LeakyBucketRateLimiterConfiguration configuration,
                                                                    final MeterRegistry meterRegistry) {

    super(connection, clock, configuration, meterRegistry);
  }

  @Override
  protected String getBucketName(final Phonenumber.PhoneNumber phoneNumber) {
    return "send-sms-verification-code-per-number::" + PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
  }

  @Override
  protected boolean shouldFailOpen() {
    return true;
  }
}
