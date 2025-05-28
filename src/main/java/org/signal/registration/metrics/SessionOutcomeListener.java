/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.metrics;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.configuration.metrics.annotation.RequiresMetrics;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.session.FailedSendAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A session outcome listener reports basic metrics about completed sessions.
 */
@Singleton
@RequiresMetrics
public class SessionOutcomeListener implements ApplicationEventListener<SessionCompletedEvent> {

  private final MeterRegistry meterRegistry;

  private static final String ATTEMPTS_COUNTER_NAME =
      MetricsUtil.name(SessionOutcomeListener.class, "completedAttempts");

  private static final String SESSIONS_COUNTER_NAME =
      MetricsUtil.name(SessionOutcomeListener.class, "completedSessions");

  private static final String SEND_ERROR_COUNTER_NAME =
      MetricsUtil.name(SessionOutcomeListener.class, "sendErrors");

  private static final Logger logger = LoggerFactory.getLogger(SessionOutcomeListener.class);

  public SessionOutcomeListener(final MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void onApplicationEvent(final SessionCompletedEvent event) {
    final RegistrationSession session = event.session();

    try {
      final Phonenumber.PhoneNumber phoneNumber =
          PhoneNumberUtil.getInstance().parse(event.session().getPhoneNumber(), null);

      for (int i = 0; i < session.getFailedAttemptsCount(); i++) {
        final FailedSendAttempt failedAttempt = session.getFailedAttempts(i);
        meterRegistry.counter(SEND_ERROR_COUNTER_NAME,
                "error", failedAttempt.getFailedSendReason().toString(),
                MetricsUtil.SENDER_TAG_NAME, failedAttempt.getSenderName(),
                MetricsUtil.TRANSPORT_TAG_NAME, MetricsUtil.getMessageTransportTagValue(failedAttempt.getMessageTransport()),
                MetricsUtil.COUNTRY_CODE_TAG_NAME, String.valueOf(phoneNumber.getCountryCode()),
                MetricsUtil.REGION_CODE_TAG_NAME, Optional.ofNullable(PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber))
                    .orElse("XX"))
            .increment();
      }

      boolean sessionVerified = false;

      // NOTE: we do not count sessions that have no attempts or only failed attempts; these could happen because the
      // attempts were blocked due to suspected fraud (among other reasons). Regardless of the cause, we only want to
      // measure sessions that actually triggered an attempt to deliver a verification code with this counter.
      for (int i = 0; i < session.getRegistrationAttemptsCount(); i++) {
        // Assume that all verification attempts before the last one were not successfully verified
        final boolean attemptVerified = StringUtils.isNotBlank(session.getVerifiedCode()) &&
            i == session.getRegistrationAttemptsCount() - 1;
        sessionVerified = attemptVerified;

        meterRegistry.counter(ATTEMPTS_COUNTER_NAME,
                MetricsUtil.SENDER_TAG_NAME, session.getRegistrationAttempts(i).getSenderName(),
                MetricsUtil.TRANSPORT_TAG_NAME, MetricsUtil.getMessageTransportTagValue(session.getRegistrationAttempts(i).getMessageTransport()),
                MetricsUtil.VERIFIED_TAG_NAME, String.valueOf(attemptVerified),
                MetricsUtil.COUNTRY_CODE_TAG_NAME, String.valueOf(phoneNumber.getCountryCode()),
                MetricsUtil.REGION_CODE_TAG_NAME, Optional.ofNullable(PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber))
                    .orElse("XX"),
                MetricsUtil.FIRST_ATTEMPT_TAG_NAME, String.valueOf(i == 0))
            .increment();
      }

      // We consider a session completed only if it had a valid attempt
      if (session.getRegistrationAttemptsCount() > 0) {
        meterRegistry.counter(SESSIONS_COUNTER_NAME,
                MetricsUtil.VERIFIED_TAG_NAME, String.valueOf(sessionVerified),
                MetricsUtil.COUNTRY_CODE_TAG_NAME, String.valueOf(phoneNumber.getCountryCode()),
                MetricsUtil.REGION_CODE_TAG_NAME,
                Optional.ofNullable(PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber))
                    .orElse("XX"))
            .increment();
      }

    } catch (final NumberParseException e) {
      logger.warn("Failed to parse phone number from completed session", e);
    }
  }
}
