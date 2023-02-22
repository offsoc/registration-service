/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.firestore;

import com.google.cloud.firestore.Firestore;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import java.time.Clock;
import java.util.concurrent.Executor;

@Named("session-creation")
@Requires(bean = Firestore.class)
@Requires(bean = FirestoreSessionCreationRateLimiterConfiguration.class)
public class FirestoreSessionCreationRateLimiter extends FirestoreLeakyBucketRateLimiter<Phonenumber.PhoneNumber> {

  public FirestoreSessionCreationRateLimiter(final Firestore firestore,
      @Named(TaskExecutors.IO) final Executor executor,
      final FirestoreSessionCreationRateLimiterConfiguration configuration,
      final Clock clock) {

    super(firestore, executor, clock, configuration.collectionName(), configuration.expirationFieldName(),
        configuration.maxCapacity(), configuration.permitRegenerationPeriod(), configuration.minDelay());
  }

  @Override
  protected String getDocumentId(final Phonenumber.PhoneNumber phoneNumber) {
    return PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
  }
}