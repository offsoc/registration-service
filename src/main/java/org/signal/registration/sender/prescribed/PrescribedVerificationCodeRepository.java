/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.prescribed;

import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Map;

/**
 * A prescribed verification code repository stores and retrieves prescribed verification codes for a set of
 * generally-fictitious phone numbers.
 *
 * @see PrescribedVerificationCodeSender
 */
public interface PrescribedVerificationCodeRepository {

  /**
   * Retrieves a map of all phone numbers with prescribed verification codes to their prescribed verification codes.
   *
   * @return a map of all phone numbers with prescribed verification codes to their prescribed verification codes
   */
  Map<Phonenumber.PhoneNumber, String> getVerificationCodes();
}
