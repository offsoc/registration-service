/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.fictitious;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.VerificationCodeGenerator;

class FictitiousNumberVerificationCodeSenderTest {

  private VerificationCodeGenerator verificationCodeGenerator;
  private FictitiousNumberVerificationCodeRepository repository;

  private FictitiousNumberVerificationCodeSender sender;

  @BeforeEach
  void setUp() {
    verificationCodeGenerator = mock(VerificationCodeGenerator.class);
    repository = mock(FictitiousNumberVerificationCodeRepository.class);

    sender = new FictitiousNumberVerificationCodeSender(verificationCodeGenerator, repository);
  }

  @ParameterizedTest
  @CsvSource({
      "+12025550123, true",
      "+12022243121, false",
      "+447700900123, true",
      "+442072193000, false",
      "+33639981234, true",
      "+33142342000, false"
  })
  void supportsDestination(final String e164, final boolean expectSupported) throws NumberParseException {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse(e164, null);

    assertEquals(expectSupported,
        sender.supportsLanguage(MessageTransport.SMS, phoneNumber, Collections.emptyList()));
  }

  @Test
  void sendVerificationCode() throws InvalidProtocolBufferException {
    final String verificationCode = "987654";
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    when(verificationCodeGenerator.generateVerificationCode()).thenReturn(verificationCode);

    final byte[] sessionDataBytes =
        sender.sendVerificationCode(MessageTransport.SMS, phoneNumber, Collections.emptyList(), ClientType.UNKNOWN).senderData();

    verify(repository).storeVerificationCode(phoneNumber, verificationCode, sender.getAttemptTtl());

    final FictitiousNumberVerificationCodeSessionData sessionData =
        FictitiousNumberVerificationCodeSessionData.parseFrom(sessionDataBytes);

    assertEquals(verificationCode, sessionData.getVerificationCode());
  }

  @Test
  void checkVerificationCode() {
    final String verificationCode = "456789";

    final FictitiousNumberVerificationCodeSessionData senderData =
        FictitiousNumberVerificationCodeSessionData.newBuilder()
            .setVerificationCode(verificationCode)
            .build();

    assertTrue(sender.checkVerificationCode(verificationCode, senderData.toByteArray()));
    assertFalse(sender.checkVerificationCode(verificationCode + "-incorrect", senderData.toByteArray()));
  }
}
