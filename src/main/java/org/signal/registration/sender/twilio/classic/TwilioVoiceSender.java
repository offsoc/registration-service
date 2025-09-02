/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.Phonenumber;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.MessageSource;
import io.micronaut.context.i18n.ResourceBundleMessageSource;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.twilio.TwilioExceptions;

/**
 * A concrete implementation of an {@code AbstractTwilioProvidedCodeSender} that sends its codes via the Twilio
 * Programmable Voice API.
 */
@Singleton
public class TwilioVoiceSender extends AbstractTwilioProvidedCodeSender implements VerificationCodeSender {

  private final TwilioRestClient twilioRestClient;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final TwilioVoiceConfiguration configuration;
  private final ApiClientInstrumenter apiClientInstrumenter;
  private final MessageSource twimlMessageSource =
      new ResourceBundleMessageSource("org.signal.registration.twilio.voice.twiml");

  public static final String SENDER_NAME = "twilio-programmable-voice";

  private static final String DEFAULT_LANGUAGE = "en-US";

  public TwilioVoiceSender(final TwilioRestClient twilioRestClient,
      final VerificationCodeGenerator verificationCodeGenerator,
      final TwilioVoiceConfiguration configuration,
      final ApiClientInstrumenter apiClientInstrumenter) {

    this.twilioRestClient = twilioRestClient;
    this.verificationCodeGenerator = verificationCodeGenerator;
    this.configuration = configuration;
    this.apiClientInstrumenter = apiClientInstrumenter;
  }

  @Override
  public String getName() {
    return SENDER_NAME;
  }

  @Override
  public Duration getAttemptTtl() {
    return configuration.getSessionTtl();
  }

  @Override
  public boolean supportsTransport(final MessageTransport transport) {
    return transport == MessageTransport.VOICE;
  }

  @Override
  public boolean supportsLanguage(
      final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges) {
    return Locale.lookupTag(languageRanges, configuration.getSupportedLanguages()) != null;
  }

  @Override
  public AttemptData sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws SenderRejectedRequestException {

    if (messageTransport != MessageTransport.VOICE) {
      throw new UnsupportedMessageTransportException();
    }

    final PhoneNumber fromPhoneNumber = configuration.getPhoneNumbers()
        .get(ThreadLocalRandom.current().nextInt(configuration.getPhoneNumbers().size()));

    final String languageTag = Optional.ofNullable(
            Locale.lookupTag(languageRanges, configuration.getSupportedLanguages()))
        .orElse(DEFAULT_LANGUAGE);

    final String verificationCode = verificationCodeGenerator.generateVerificationCode();

    final Timer.Sample sample = Timer.start();

    try {
      final Call call = Call.creator(twilioNumberFromPhoneNumber(phoneNumber),
              fromPhoneNumber,
              buildCallTwiml(verificationCode, languageTag))
          .create(twilioRestClient);

      this.apiClientInstrumenter.recordApiCallMetrics(
          this.getName(),
          "call.create",
          true,
          null,
          sample);

      return buildAttemptMetadata(call.getSid(), verificationCode);
    } catch (final ApiException e) {
      this.apiClientInstrumenter.recordApiCallMetrics(
          this.getName(),
          "call.create",
          false,
          TwilioExceptions.extractErrorCode(null),
          sample);

      throw TwilioExceptions.toSenderRejectedException(e)
          .orElseThrow(() -> new UncheckedIOException(new IOException(e)));
    }
  }

  @VisibleForTesting
  Twiml buildCallTwiml(final String verificationCode, final String languageTag) {
    final URI cdnUriWithLocale = configuration.getCdnUri().resolve(languageTag + "/");

    return new Twiml(twimlMessageSource.getRequiredMessage("twilio.voice.twiml",
        MessageSource.MessageContext.of(Map.of(
            "verification", cdnUriWithLocale.resolve("verification.mp3"),
            "code0", cdnUriWithLocale.resolve(verificationCode.charAt(0) + "_middle.mp3"),
            "code1", cdnUriWithLocale.resolve(verificationCode.charAt(1) + "_middle.mp3"),
            "code2", cdnUriWithLocale.resolve(verificationCode.charAt(2) + "_middle.mp3"),
            "code3", cdnUriWithLocale.resolve(verificationCode.charAt(3) + "_middle.mp3"),
            "code4", cdnUriWithLocale.resolve(verificationCode.charAt(4) + "_middle.mp3"),
            "code5", cdnUriWithLocale.resolve(verificationCode.charAt(5) + "_falling.mp3")
        ))));
  }
}
