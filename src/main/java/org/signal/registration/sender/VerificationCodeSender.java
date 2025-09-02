/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import com.google.i18n.phonenumbers.Phonenumber;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * A verification code sender is responsible for sending verification codes to phone numbers and later verifying codes
 * provided by clients. A verification code sender sends verification codes via at least one transport mechanism.
 * <p>
 * Verification code senders may generate their own verification codes or may rely on an external service to manage
 * verification codes. Either way, senders generally need to preserve some state associated with a registration session
 * (either the generated verification code or a reference to a session or object managed by an external service). To do
 * so, senders return a {@link AttemptData} object containing any data that needs to be preserved from the
 * {@link #sendVerificationCode(MessageTransport, Phonenumber.PhoneNumber, List, ClientType)} method; those data will
 * be provided later to the {@link #checkVerificationCode(String, byte[])} method when a client provides a
 * verification code in the context of the same registration session.
 */
public interface VerificationCodeSender {

  /**
   * Returns the name of this sender. Names are used to uniquely identify senders in several contexts (and especially
   * for identifying the sender associated with a {@link org.signal.registration.session.RegistrationSession}) and must
   * be globally unique.
   *
   * @return the name of this sender
   */
  String getName();

  /**
   * Returns the lifetime of registration attempts associated with this sender.
   *
   * @return the lifetime of registration attempts associated with this sender
   */
  Duration getAttemptTtl();

  /**
   * Return whether this sender supports the given transport.
   *
   * @param transport the requested transport
   * @return if the sender supports the transport
   */
  boolean supportsTransport(MessageTransport transport);

  /**
   * Indicates whether this sender can deliver messages to the given phone number using any of the given languages.
   *
   * @param messageTransport the transport via which to send a verification code
   * @param phoneNumber      the phone number to which to send a verification code
   * @param languageRanges   the preferred languages in which to send verification codes
   * @return {@code true} if this sender can send notifications to the given destination or {@code false} if not
   */
  boolean supportsLanguage(
      MessageTransport messageTransport,
      Phonenumber.PhoneNumber phoneNumber,
      List<Locale.LanguageRange> languageRanges);

  /**
   * Sends a verification code to the given phone number with the given preferred languages. This method returns an
   * opaque string to be stored as part of the registration session that triggered this request to send a verification
   * code; later, the same string will be provided to the {@link #checkVerificationCode(String, byte[])} method when
   * called in the context of the same registration session.
   *
   * @param messageTransport the transport via which to send a verification code
   * @param phoneNumber      the phone number to which to send a verification code
   * @param languageRanges   the preferred languages in which to send verification codes
   * @param clientType       the type of client receiving the verification code
   *
   * @return attempt data to be provided to the {@link #checkVerificationCode(String, byte[])} method later
   *
   * @throws SenderRejectedRequestException if the sender received, but affirmatively rejected, the request
   */
  AttemptData sendVerificationCode(MessageTransport messageTransport,
      Phonenumber.PhoneNumber phoneNumber,
      List<Locale.LanguageRange> languageRanges,
      ClientType clientType) throws SenderRejectedRequestException;

  /**
   * Checks whether the verification code provided by a client matches the verification code sent via an earlier call to
   * {@link #sendVerificationCode(MessageTransport, Phonenumber.PhoneNumber, List, ClientType)}.
   *
   * @param verificationCode the verification code provided by a client
   * @param senderData the sender data returned by
   *                   {@link #sendVerificationCode(MessageTransport, Phonenumber.PhoneNumber, List, ClientType)}
   *                   earlier in this registration session
   *
   * @return {@code true} if the provided {@code verificationCode} matches the expected verification code associated
   * with this session
   *
   * @throws SenderRejectedRequestException if the sender received, but affirmatively rejected, the request
   */
  boolean checkVerificationCode(String verificationCode, byte[] senderData) throws SenderRejectedRequestException;
}
