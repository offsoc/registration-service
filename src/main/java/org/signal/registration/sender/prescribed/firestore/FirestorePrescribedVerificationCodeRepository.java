/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.prescribed.firestore;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Firestore prescribed verification code repository reads prescribed verification codes from a
 * <a href="https://firebase.google.com/docs/firestore">Cloud Firestore</a> collection. Prescribed verification codes
 * are generally managed externally.
 */
@Requires(bean = Firestore.class)
@Singleton
class FirestorePrescribedVerificationCodeRepository implements PrescribedVerificationCodeRepository {

  private final Firestore firestore;
  private final FirestorePrescribedVerificationCodeRepositoryConfiguration configuration;

  private final Timer getVerificationCodesTimer;

  @VisibleForTesting
  static final String VERIFICATION_CODE_KEY = "verification-code";

  private static final Logger logger = LoggerFactory.getLogger(FirestorePrescribedVerificationCodeRepository.class);

  public FirestorePrescribedVerificationCodeRepository(final Firestore firestore,
      final FirestorePrescribedVerificationCodeRepositoryConfiguration configuration, final MeterRegistry meterRegistry) {

    this.firestore = firestore;
    this.configuration = configuration;

    getVerificationCodesTimer = meterRegistry.timer(MetricsUtil.name(FirestorePrescribedVerificationCodeRepository.class, "getVerificationCodes"));
  }

  @Override
  public Map<Phonenumber.PhoneNumber, String> getVerificationCodes() {
    final Timer.Sample sample = Timer.start();

    try {
      final QuerySnapshot querySnapshot = firestore.collection(configuration.getCollectionName()).get().get();

      final Map<Phonenumber.PhoneNumber, String> verificationCodes =
          new HashMap<>(querySnapshot.getDocuments().size());

      for (final QueryDocumentSnapshot documentSnapshot : querySnapshot.getDocuments()) {
        try {
          final String e164 = documentSnapshot.getId().startsWith("+") ?
              documentSnapshot.getId() : "+" + documentSnapshot.getId();

          final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse(e164, null);
          final String verificationCode = documentSnapshot.getString(VERIFICATION_CODE_KEY);

          if (StringUtils.isNotBlank(verificationCode)) {
            verificationCodes.put(phoneNumber, verificationCode);
          } else {
            logger.warn("No verification code found for {}",
                PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164));
          }
        } catch (final NumberParseException e) {
          logger.warn("Failed to parse document ID as phone number: {}", documentSnapshot.getId());
        }
      }

      return verificationCodes;
    } catch (final ExecutionException | InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      sample.stop(getVerificationCodesTimer);
    }
  }
}
