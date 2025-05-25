/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.rpc.RegistrationSessionMetadata;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.SenderRejectedTransportException;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.FailedSendAttempt;
import org.signal.registration.session.FailedSendReason;
import org.signal.registration.session.MemorySessionRepository;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionMetadata;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.CompletionExceptions;
import org.signal.registration.util.UUIDUtil;

class RegistrationServiceTest {

  private RegistrationService registrationService;

  private VerificationCodeSender sender;
  private SessionRepository sessionRepository;
  private RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter;
  private RateLimiter<RegistrationSession> sendSmsVerificationCodePerSessionRateLimiter;
  private RateLimiter<RegistrationSession> sendVoiceVerificationCodePerSessionRateLimiter;
  private RateLimiter<RegistrationSession> checkVerificationCodePerSessionRateLimiter;
  private RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter;
  private RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter;
  private RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter;
  private Clock clock;
  private SenderSelectionStrategy senderSelectionStrategy;

  private static final Phonenumber.PhoneNumber PHONE_NUMBER = PhoneNumberUtil.getInstance().getExampleNumber("US");
  private static final String SENDER_NAME = "mock-sender";
  private static final Duration SESSION_TTL = Duration.ofSeconds(17);
  private static final String VERIFICATION_CODE = "654321";
  private static final byte[] VERIFICATION_CODE_BYTES = VERIFICATION_CODE.getBytes(StandardCharsets.UTF_8);
  private static final List<Locale.LanguageRange> LANGUAGE_RANGES = Locale.LanguageRange.parse("en,de");
  private static final ClientType CLIENT_TYPE = ClientType.UNKNOWN;
  private static final SessionMetadata SESSION_METADATA = SessionMetadata.newBuilder().build();
  private static final Instant CURRENT_TIME = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  @BeforeEach
  void setUp() {
    sender = mock(VerificationCodeSender.class);
    when(sender.getName()).thenReturn(SENDER_NAME);
    when(sender.getAttemptTtl()).thenReturn(SESSION_TTL);

    clock = mock(Clock.class);
    when(clock.instant()).thenReturn(CURRENT_TIME);
    when(clock.millis()).thenReturn(CURRENT_TIME.toEpochMilli());

    //noinspection unchecked
    sessionRepository = spy(new MemorySessionRepository(mock(ApplicationEventPublisher.class), clock));

    senderSelectionStrategy = mock(SenderSelectionStrategy.class);
    when(senderSelectionStrategy.chooseVerificationCodeSender(any(), any(), any(), any(), any(), any()))
        .thenReturn(new SenderSelectionStrategy.SenderSelection(sender, SenderSelectionStrategy.SelectionReason.CONFIGURED));

    //noinspection unchecked
    sessionCreationRateLimiter = mock(RateLimiter.class);
    when(sessionCreationRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    //noinspection unchecked
    sendSmsVerificationCodePerSessionRateLimiter = mock(RateLimiter.class);
    when(sendSmsVerificationCodePerSessionRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));
    when(sendSmsVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(CURRENT_TIME)));

    //noinspection unchecked
    sendVoiceVerificationCodePerSessionRateLimiter = mock(RateLimiter.class);
    when(sendVoiceVerificationCodePerSessionRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));
    when(sendVoiceVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(CURRENT_TIME)));

    //noinspection unchecked
    checkVerificationCodePerSessionRateLimiter = mock(RateLimiter.class);
    when(checkVerificationCodePerSessionRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));
    when(checkVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(CURRENT_TIME)));

    //noinspection unchecked
    sendSmsVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);
    when(sendSmsVerificationCodePerNumberRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    //noinspection unchecked
    sendVoiceVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);
    when(sendVoiceVerificationCodePerNumberRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    //noinspection unchecked
    checkVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);
    when(checkVerificationCodePerNumberRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    registrationService = new RegistrationService(senderSelectionStrategy,
        sessionRepository,
        sessionCreationRateLimiter,
        sendSmsVerificationCodePerSessionRateLimiter,
        sendVoiceVerificationCodePerSessionRateLimiter,
        checkVerificationCodePerSessionRateLimiter,
        sendSmsVerificationCodePerNumberRateLimiter,
        sendVoiceVerificationCodePerNumberRateLimiter,
        checkVerificationCodePerNumberRateLimiter,
        List.of(sender),
        clock);
  }

  @Test
  void createSession() {
    final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();

    assertEquals(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164),
        session.getPhoneNumber());

    assertTrue(session.getExpirationEpochMillis() > CURRENT_TIME.toEpochMilli());
    assertFalse(session.getId().isEmpty());
  }

  @Test
  void createSessionRateLimited() {
    final RateLimitExceededException rateLimitExceededException = new RateLimitExceededException(Duration.ZERO);

    when(sessionCreationRateLimiter.checkRateLimit(any()))
        .thenReturn(CompletableFuture.failedFuture(rateLimitExceededException));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join());

    assertEquals(rateLimitExceededException, CompletionExceptions.unwrap(completionException));
    verify(sessionRepository, never()).createSession(any(), any(), any());
  }

  @Test
  void sendVerificationCode() {
    final String remoteId = UUID.randomUUID().toString();

    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());
    }

    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(new AttemptData(Optional.of(remoteId), VERIFICATION_CODE_BYTES)));

    final RegistrationSession session =
        registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();

    verify(sender).sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE);
    verify(sendSmsVerificationCodePerSessionRateLimiter).checkRateLimit(any());
    verify(sendVoiceVerificationCodePerSessionRateLimiter, never()).checkRateLimit(any());
    verify(sessionRepository).updateSession(eq(sessionId), any());

    assertEquals(1, session.getRegistrationAttemptsCount());
    assertEquals(remoteId, session.getRegistrationAttempts(0).getRemoteId());
    assertEquals(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS,
        session.getRegistrationAttemptsList().get(0).getMessageTransport());
  }

  @Test
  void previouslyFailedSenders() {
    RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
    final UUID sessionId = UUIDUtil.uuidFromByteString(session.getId());

    // attempt failed due to sender being unavailable
    when(sender.getName()).thenReturn("sender1");
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.failedFuture(new Exception("sender unavailable")));

    assertThrows(CompletionException.class, () -> {
      registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, "sender1", LANGUAGE_RANGES, CLIENT_TYPE).join();
    });

    // attempt failed due to fraud block
    when(sender.getName()).thenReturn("sender2");
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.failedFuture(new SenderFraudBlockException("fraud block")));

    assertThrows(CompletionException.class, () -> {
      registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, "sender2", LANGUAGE_RANGES, CLIENT_TYPE).join();
    });

    // successful send that is not verified will be in the failed sender list after a subsequent send
    when(sender.getName()).thenReturn("sender3");
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(new AttemptData(Optional.of("third"), "code".getBytes(StandardCharsets.UTF_8))));

    registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, "sender3", LANGUAGE_RANGES, CLIENT_TYPE).join();

    // last attempt
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(new AttemptData(Optional.of("fourth"), "code".getBytes(StandardCharsets.UTF_8))));
    registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, "sender4", LANGUAGE_RANGES, CLIENT_TYPE).join();

    session = sessionRepository.getSession(sessionId).join();

    assertEquals(2, session.getRegistrationAttemptsCount());
    assertEquals(2, session.getFailedAttemptsCount());

    //noinspection unchecked
    final ArgumentCaptor<Set<String>> previouslyFailedSenderListCaptor =
        ArgumentCaptor.forClass(Set.class);

    verify(senderSelectionStrategy, times(4)).chooseVerificationCodeSender(
        eq(MessageTransport.SMS),
        eq(PHONE_NUMBER),
        eq(LANGUAGE_RANGES),
        eq(CLIENT_TYPE),
        any(),
        previouslyFailedSenderListCaptor.capture()
    );

    assertEquals(Set.of("sender1", "sender3"), previouslyFailedSenderListCaptor.getValue());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sendVerificationCodeSmsRateLimited(final boolean sessionRateLimited) {
    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());
    }

    if (sessionRateLimited) {
      when(sendSmsVerificationCodePerSessionRateLimiter.checkRateLimit(any()))
          .thenAnswer(answer -> CompletableFuture.failedFuture(
              new RateLimitExceededException(null, answer.getArgument(0, RegistrationSession.class))));
    } else {
      when(sendSmsVerificationCodePerNumberRateLimiter.checkRateLimit(any()))
          .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(null, null)));
    }

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join());

    final RateLimitExceededException e = assertInstanceOf(RateLimitExceededException.class, CompletionExceptions.unwrap(completionException));
    assertTrue(e.getRegistrationSession().isPresent());

    verify(sender, never()).sendVerificationCode(any(), any(), any(), any());
    verify(sendSmsVerificationCodePerSessionRateLimiter).checkRateLimit(any());
    verify(sendVoiceVerificationCodePerSessionRateLimiter, never()).checkRateLimit(any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sendVerificationCodeVoiceRateLimited(final boolean sessionRateLimited) {
    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());
    }

    if (sessionRateLimited) {
      when(sendVoiceVerificationCodePerSessionRateLimiter.checkRateLimit(any()))
          .thenAnswer(answer -> CompletableFuture.failedFuture(
              new RateLimitExceededException(null, answer.getArgument(0, RegistrationSession.class))));
    } else {
      when(sendVoiceVerificationCodePerNumberRateLimiter.checkRateLimit(any()))
          .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(null, null)));
    }

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.sendVerificationCode(MessageTransport.VOICE, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join());

    final RateLimitExceededException e = assertInstanceOf(RateLimitExceededException.class, CompletionExceptions.unwrap(completionException));
    assertTrue(e.getRegistrationSession().isPresent(), "Callers expect the session to be present");

    verify(sender, never()).sendVerificationCode(any(), any(), any(), any());
    verify(sendSmsVerificationCodePerSessionRateLimiter, never()).checkRateLimit(any());
    verify(sendVoiceVerificationCodePerSessionRateLimiter).checkRateLimit(any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @Test
  void sendVerificationCodeTransportNotAllowed() {
    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());
    }

    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.failedFuture(new SenderRejectedTransportException(new RuntimeException())));

    final CompletionException completionException = assertThrows(CompletionException.class, () -> {
      registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();
    });

    assertInstanceOf(TransportNotAllowedException.class, completionException.getCause());

    final TransportNotAllowedException transportNotAllowedException =
        (TransportNotAllowedException) completionException.getCause();

    verify(sender).sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE);
    verify(sendSmsVerificationCodePerSessionRateLimiter).checkRateLimit(any());
    verify(sendVoiceVerificationCodePerSessionRateLimiter, never()).checkRateLimit(any());
    verify(sessionRepository).updateSession(eq(sessionId), any());

    final RegistrationSession session = transportNotAllowedException.getRegistrationSession();

    assertEquals(0, session.getRegistrationAttemptsCount());
    assertEquals(List.of(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS),
        session.getRejectedTransportsList());
  }

  public static Stream<Arguments> sendVerificationCodeRejectionError() {
    return Stream.of(
        Arguments.of(new SenderRejectedRequestException(new RuntimeException()), FailedSendReason.FAILED_SEND_REASON_REJECTED),
        Arguments.of(new SenderFraudBlockException(new RuntimeException()), FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD),
        Arguments.of(new RuntimeException(), FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
    );
  }

  @ParameterizedTest
  @MethodSource
  void sendVerificationCodeRejectionError(Throwable senderException, FailedSendReason expectedFailureReason) {
    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());
    }

    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.failedFuture(senderException));

    assertThrows(CompletionException.class, () -> {
      registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();
    });

    verify(sender).sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE);
    verify(sendSmsVerificationCodePerSessionRateLimiter).checkRateLimit(any());
    verify(sendVoiceVerificationCodePerSessionRateLimiter, never()).checkRateLimit(any());
    verify(sessionRepository).updateSession(eq(sessionId), any());

    final RegistrationSession session = sessionRepository.getSession(sessionId).join();

    assertEquals(0, session.getRegistrationAttemptsCount());
    assertEquals(1, session.getFailedAttemptsCount());
    assertEquals(session.getFailedAttempts(0).getFailedSendReason(), expectedFailureReason);
  }

  @Test
  void registrationAttempts() {
    final String firstVerificationCode = "123456";
    final String secondVerificationCode = "234567";

    when(sender.sendVerificationCode(any(), eq(PHONE_NUMBER), eq(LANGUAGE_RANGES), eq(CLIENT_TYPE)))
        .thenReturn(CompletableFuture.completedFuture(new AttemptData(Optional.of("first"), firstVerificationCode.getBytes(StandardCharsets.UTF_8))))
        .thenReturn(CompletableFuture.completedFuture(new AttemptData(Optional.of("second"), secondVerificationCode.getBytes(StandardCharsets.UTF_8))));

    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());
    }

    {
      final RegistrationSession session =
          registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

      final ByteString expectedSenderData = ByteString.copyFromUtf8(firstVerificationCode);

      assertEquals(1, session.getRegistrationAttemptsList().size());

      final RegistrationAttempt firstAttempt = session.getRegistrationAttempts(0);
      assertEquals(sender.getName(), firstAttempt.getSenderName());
      assertEquals(CURRENT_TIME.toEpochMilli(), firstAttempt.getTimestampEpochMillis());
      assertEquals(expectedSenderData, firstAttempt.getSenderData());
      assertEquals(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS, firstAttempt.getMessageTransport());
    }

    final Instant future = CURRENT_TIME.plus(SESSION_TTL.dividedBy(2));
    when(clock.instant()).thenReturn(future);
    when(clock.millis()).thenReturn(future.toEpochMilli());

    {
      final RegistrationSession session =
          registrationService.sendVerificationCode(MessageTransport.VOICE, sessionId, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

      final ByteString expectedSenderData = ByteString.copyFromUtf8(secondVerificationCode);

      assertEquals(2, session.getRegistrationAttemptsList().size());

      final RegistrationAttempt secondAttempt = session.getRegistrationAttempts(1);
      assertEquals(sender.getName(), secondAttempt.getSenderName());
      assertEquals(future.toEpochMilli(), secondAttempt.getTimestampEpochMillis());
      assertEquals(expectedSenderData, secondAttempt.getSenderData());
      assertEquals(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_VOICE, secondAttempt.getMessageTransport());
    }
  }

  @Test
  void checkVerificationCode() {
    final AttemptData attemptData = new AttemptData(Optional.of("test"), VERIFICATION_CODE_BYTES);

    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(attemptData));

    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());

      registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();
    }

    when(sender.checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES))
        .thenReturn(CompletableFuture.completedFuture(true));

    final RegistrationSession session = registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join();

    assertEquals(VERIFICATION_CODE, session.getVerifiedCode());
    assertEquals(1, session.getCheckCodeAttempts());
    assertEquals(CURRENT_TIME.toEpochMilli(), session.getLastCheckCodeAttemptEpochMillis());

    verify(sender).checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES);
  }

  @Test
  void checkVerificationCodeResend() {
    final AttemptData attemptData = new AttemptData(Optional.of("test"), VERIFICATION_CODE_BYTES);

    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());
    }

    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(attemptData));

    registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();

    when(sender.checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES))
        .thenReturn(CompletableFuture.completedFuture(false));

    {
      final RegistrationSession session =
          registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join();

      assertTrue(StringUtils.isBlank(session.getVerifiedCode()));
      assertEquals(1, session.getCheckCodeAttempts());
      assertEquals(CURRENT_TIME.toEpochMilli(), session.getLastCheckCodeAttemptEpochMillis());
    }

    {
      final RegistrationSession session =
          registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();

      assertTrue(StringUtils.isBlank(session.getVerifiedCode()));
      assertEquals(0, session.getCheckCodeAttempts());
      assertEquals(0, session.getLastCheckCodeAttemptEpochMillis());
    }

    verify(sender).checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES);
  }

  @Test
  void checkVerificationCodeSessionNotFound() {
    final UUID sessionId = UUID.randomUUID();

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join());

    assertInstanceOf(SessionNotFoundException.class, CompletionExceptions.unwrap(completionException));

    verify(sessionRepository).getSession(sessionId);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @Test
  void checkVerificationCodePreviouslyVerified() {
    final UUID sessionId = UUID.randomUUID();

    when(sessionRepository.getSession(sessionId))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .setVerifiedCode(VERIFICATION_CODE)
                .build()));

    assertEquals(VERIFICATION_CODE, registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join().getVerifiedCode());

    verify(sessionRepository).getSession(sessionId);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void checkVerificationCodeRateLimited(final boolean sessionLimitExceeded) {
    final UUID sessionId = UUID.randomUUID();

    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(sessionId))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setSenderName(SENDER_NAME)
            .setSenderData(ByteString.copyFrom(VERIFICATION_CODE_BYTES))
            .setExpirationEpochMillis(CURRENT_TIME.toEpochMilli() + 1)
            .build())
        .build();

    when(sessionRepository.getSession(sessionId))
        .thenReturn(CompletableFuture.completedFuture(session));

    final Duration retryAfterDuration = Duration.ofMinutes(17);

    if (sessionLimitExceeded) {
      when(checkVerificationCodePerSessionRateLimiter.checkRateLimit(session))
          .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(retryAfterDuration, session)));
    } else {
      when(checkVerificationCodePerNumberRateLimiter.checkRateLimit(PHONE_NUMBER))
          .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(retryAfterDuration)));
    }

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join());

    final RateLimitExceededException rateLimitExceededException =
        (RateLimitExceededException) CompletionExceptions.unwrap(completionException);

    assertEquals(Optional.of(session), rateLimitExceededException.getRegistrationSession());
    assertEquals(Optional.of(retryAfterDuration), rateLimitExceededException.getRetryAfterDuration());

    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @Test
  void checkRegistrationCodeAttemptExpired() {
    final UUID sessionId = UUID.randomUUID();

    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(sessionId))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setSenderName(SENDER_NAME)
            .setSenderData(ByteString.copyFrom(VERIFICATION_CODE_BYTES))
            .setExpirationEpochMillis(CURRENT_TIME.toEpochMilli() - 1)
            .build())
        .build();

    when(sessionRepository.getSession(sessionId))
        .thenReturn(CompletableFuture.completedFuture(session));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join());

    assertInstanceOf(AttemptExpiredException.class, CompletionExceptions.unwrap(completionException));

    verify(sessionRepository).getSession(sessionId);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @Test
  void checkVerificationCodeSenderRejected() {
    final AttemptData attemptData = new AttemptData(Optional.of("test"), VERIFICATION_CODE_BYTES);

    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(attemptData));

    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());

      registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();
    }

    when(sender.checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES))
        .thenReturn(CompletableFuture.failedFuture(new CompletionException(new SenderRejectedRequestException("sender rejected"))));

    final RegistrationSession session = registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join();

    assertTrue(session.getVerifiedCode().isEmpty());
    assertEquals(1, session.getCheckCodeAttempts());
    assertEquals(CURRENT_TIME.toEpochMilli(), session.getLastCheckCodeAttemptEpochMillis());
  }

  @ParameterizedTest
  @MethodSource
  void getNextActionTimes(final RegistrationSession session,
      final boolean allowSms,
      final boolean allowVoiceCall,
      final boolean allowCodeCheck,
      final boolean expectNextSms,
      final boolean expectNextVoiceCall,
      final boolean expectNextCodeCheck) {

    final long nextSmsSeconds = 17;
    final long nextVoiceCallSeconds = 19;
    final long nextCodeCheckSeconds = 23;

    when(sendSmsVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(allowSms
            ? Optional.of(CURRENT_TIME.plusSeconds(nextSmsSeconds))
            : Optional.empty()));

    when(sendVoiceVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(allowVoiceCall
            ? Optional.of(CURRENT_TIME.plusSeconds(nextVoiceCallSeconds))
            : Optional.empty()));

    when(checkVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(allowCodeCheck
            ? Optional.of(CURRENT_TIME.plusSeconds(nextCodeCheckSeconds))
            : Optional.empty()));

    final RegistrationService.NextActionTimes nextActionTimes =
        registrationService.getNextActionTimes(session).toCompletableFuture().join();

    assertEquals(expectNextSms ? Optional.of(CURRENT_TIME.plusSeconds(nextSmsSeconds)) : Optional.empty(),
        nextActionTimes.nextSms());

    assertEquals(expectNextVoiceCall ? Optional.of(CURRENT_TIME.plusSeconds(nextVoiceCallSeconds)) : Optional.empty(),
        nextActionTimes.nextVoiceCall());

    assertEquals(expectNextCodeCheck ? Optional.of(CURRENT_TIME.plusSeconds(nextCodeCheckSeconds)) : Optional.empty(),
        nextActionTimes.nextCodeCheck());
  }

  @ParameterizedTest
  @MethodSource("getNextActionTimes")
  void buildSessionMetadata(final RegistrationSession session,
      final boolean allowSms,
      final boolean allowVoiceCall,
      final boolean allowCodeCheck,
      final boolean expectNextSms,
      final boolean expectNextVoiceCall,
      final boolean expectNextCodeCheck) {

    final long nextSmsSeconds = 17;
    final long nextVoiceCallSeconds = 19;
    final long nextCodeCheckSeconds = 23;

    when(sendSmsVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(allowSms
            ? Optional.of(CURRENT_TIME.plusSeconds(nextSmsSeconds))
            : Optional.empty()));

    when(sendVoiceVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(allowVoiceCall
            ? Optional.of(CURRENT_TIME.plusSeconds(nextVoiceCallSeconds))
            : Optional.empty()));

    when(checkVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(allowCodeCheck
            ? Optional.of(CURRENT_TIME.plusSeconds(nextCodeCheckSeconds))
            : Optional.empty()));

    final RegistrationSessionMetadata sessionMetadata =
        registrationService.buildSessionMetadata(session).toCompletableFuture().join();

    assertEquals(session.getId(), sessionMetadata.getSessionId());
    assertEquals(
        Long.parseLong(StringUtils.removeStart(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164), "+")),
        sessionMetadata.getE164());

    assertEquals(StringUtils.isNotBlank(session.getVerifiedCode()), sessionMetadata.getVerified());
    assertEquals(expectNextSms, sessionMetadata.getMayRequestSms());
    assertEquals(expectNextSms ? nextSmsSeconds : 0, sessionMetadata.getNextSmsSeconds());
    assertEquals(expectNextVoiceCall, sessionMetadata.getMayRequestVoiceCall());
    assertEquals(expectNextVoiceCall ? nextVoiceCallSeconds : 0, sessionMetadata.getNextVoiceCallSeconds());
    assertEquals(expectNextCodeCheck, sessionMetadata.getMayCheckCode());
    assertEquals(expectNextCodeCheck ? nextCodeCheckSeconds : 0, sessionMetadata.getNextCodeCheckSeconds());
  }

  private static Stream<Arguments> getNextActionTimes() {
    return Stream.of(
        // Fresh session; unverified and no codes sent
        Arguments.of(getBaseSessionBuilder().build(),
            true, true, true,
            true, false, false),

        // Unverified session with an initial SMS sent
        Arguments.of(getBaseSessionBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setExpirationEpochMillis(CURRENT_TIME.plusSeconds(60).toEpochMilli())
                    .build())
                .build(),
            true, true, true,
            true, true, true),

        // Unverified session with an initial SMS sent, but the attempt has expired
        Arguments.of(getBaseSessionBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setExpirationEpochMillis(CURRENT_TIME.minusSeconds(60).toEpochMilli())
                    .build())
                .build(),
            true, true, true,
            true, true, false),

        // Unverified session with an initial SMS sent, but checks for the attempt have been exhausted
        Arguments.of(getBaseSessionBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setExpirationEpochMillis(CURRENT_TIME.plusSeconds(60).toEpochMilli())
                    .build())
                .build(),
            true, true, false,
            true, true, false),

        // Unverified session with SMS attempts exhausted
        Arguments.of(getBaseSessionBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setExpirationEpochMillis(CURRENT_TIME.plusSeconds(60).toEpochMilli())
                    .build())
                .build(),
            false, true, true,
            false, true, true),

        // Unverified session with SMS rejected as a transport
        Arguments.of(getBaseSessionBuilder()
                .addRejectedTransports(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                .build(),
            false, true, true,
            false, true, false),

        // Unverified session with one failed, non-fraud SMS attempt
        Arguments.of(getBaseSessionBuilder()
                .addFailedAttempts(FailedSendAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
                    .build())
                .build(),
            true, true, true,
            true, true, false),

        // Unverified session with one failed, fraud SMS attempt
        Arguments.of(getBaseSessionBuilder()
                .addFailedAttempts(FailedSendAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD)
                    .build())
                .build(),
            true, true, true,
            true, false, false),

        // Unverified session with voice calls exhausted
        Arguments.of(getBaseSessionBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setExpirationEpochMillis(CURRENT_TIME.plusSeconds(60).toEpochMilli())
                    .build())
                .build(),
            true, false, true,
            true, false, true),

        // Verified session
        Arguments.of(getBaseSessionBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .build())
                .setVerifiedCode("123456")
                .build(),
            true, true, true,
            false, false, false)
    );
  }

  private static RegistrationSession.Builder getBaseSessionBuilder() {
    return RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(UUID.randomUUID()))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164));
  }

  @Test
  void buildSessionMetadataActionInPast() {
    when(sendSmsVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(CURRENT_TIME.minusSeconds(17))));

    when(sendVoiceVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(CURRENT_TIME.minusSeconds(19))));

    when(checkVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(CURRENT_TIME.minusSeconds(23))));

    final RegistrationSession session = getBaseSessionBuilder()
        .setCreatedEpochMillis(CURRENT_TIME.toEpochMilli())
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestampEpochMillis(CURRENT_TIME.toEpochMilli())
            .setExpirationEpochMillis(CURRENT_TIME.plusSeconds(600).toEpochMilli())
            .build())
        .build();

    final RegistrationSessionMetadata metadata = registrationService.buildSessionMetadata(session).toCompletableFuture().join();

    assertTrue(metadata.getMayRequestSms());
    assertEquals(0, metadata.getNextSmsSeconds());

    assertTrue(metadata.getMayRequestVoiceCall());
    assertEquals(0, metadata.getNextVoiceCallSeconds());

    assertTrue(metadata.getMayCheckCode());
    assertEquals(0, metadata.getNextCodeCheckSeconds());
  }

  @Test
  void checkVerificationCodeSenderException() {
    final AttemptData attemptData = new AttemptData(Optional.of("test"), VERIFICATION_CODE_BYTES);

    when(sender.sendVerificationCode(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(attemptData));

    final UUID sessionId;
    {
      final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER, "", SESSION_METADATA).join();
      sessionId = UUIDUtil.uuidFromByteString(session.getId());

      registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, SENDER_NAME, LANGUAGE_RANGES, CLIENT_TYPE).join();
    }

    when(sender.checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES))
        .thenReturn(CompletableFuture.failedFuture(new SenderRejectedRequestException(new RuntimeException("OH NO"))));

    final RegistrationSession session = registrationService.checkVerificationCode(sessionId, VERIFICATION_CODE).join();

    assertTrue(StringUtils.isBlank(session.getVerifiedCode()));
    assertEquals(1, session.getCheckCodeAttempts());
    assertEquals(CURRENT_TIME.toEpochMilli(), session.getLastCheckCodeAttemptEpochMillis());
  }

  @ParameterizedTest
  @MethodSource
  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  void getSessionExpiration(final Instant sessionCreation,
      final boolean verified,
      final Instant lastCodeCheck,
      final Optional<Instant> nextSms,
      final List<Instant> attemptExpirations,
      final Instant expectedExpiration) {

    when(sendSmsVerificationCodePerSessionRateLimiter.getTimeOfNextAction(any()))
        .thenReturn(CompletableFuture.completedFuture(nextSms));

    final RegistrationSession.Builder sessionBuilder = RegistrationSession.newBuilder()
        .setCreatedEpochMillis(sessionCreation.toEpochMilli())
        .setExpirationEpochMillis(sessionCreation.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION).toEpochMilli())
        .setLastCheckCodeAttemptEpochMillis(lastCodeCheck.toEpochMilli());

    if (verified) {
      sessionBuilder.setVerifiedCode("verified");
    }

    attemptExpirations.stream()
        .map(attemptExpiration -> RegistrationAttempt.newBuilder()
            .setExpirationEpochMillis(attemptExpiration.toEpochMilli())
            .build())
        .forEach(sessionBuilder::addRegistrationAttempts);

    assertEquals(expectedExpiration,
        registrationService.getSessionExpiration(sessionBuilder.build()).toCompletableFuture().join());
  }

  private static Stream<Arguments> getSessionExpiration() {
    return Stream.of(
        // Session verified right now
        Arguments.of(CURRENT_TIME,
            true,
            CURRENT_TIME,
            Optional.empty(),
            List.of(),
            CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION)),

        // Verification code never checked, ready to send an SMS in two minutes
        Arguments.of(CURRENT_TIME.minusSeconds(600),
            false,
            Instant.ofEpochMilli(0),
            Optional.of(CURRENT_TIME.plusSeconds(120)),
            List.of(),
            CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION).plus(Duration.ofMinutes(2))),

        // Verification code never checked, not allowed to request another SMS
        Arguments.of(CURRENT_TIME.minusSeconds(600),
            false,
            Instant.ofEpochMilli(0),
            Optional.empty(),
            List.of(CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION).plus(Duration.ofMinutes(3))),
            CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION).plus(Duration.ofMinutes(3))),

        // Verification code never checked; two recent successful registration attempts
        Arguments.of(CURRENT_TIME.minusSeconds(600),
            false,
            Instant.ofEpochMilli(0),
            Optional.of(CURRENT_TIME.plusSeconds(120)),
            List.of(
                CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION).plus(Duration.ofMinutes(3)),
                CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION).plus(Duration.ofMinutes(5))),
            CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION).plus(Duration.ofMinutes(5))),

        // Fresh session with some time elapsed
        Arguments.of(
            CURRENT_TIME.minus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION.dividedBy(2)),
            false,
            Instant.ofEpochMilli(0),
            Optional.of(CURRENT_TIME.minus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION.dividedBy(2))),
            List.of(),
            CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION.dividedBy(2))),

        // No successful registration attempts, not allowed to request another SMS
        Arguments.of(
            CURRENT_TIME,
            false,
            Instant.ofEpochMilli(0),
            Optional.empty(),
            List.of(),
            CURRENT_TIME.plus(RegistrationService.SESSION_TTL_AFTER_LAST_ACTION)
        )
    );
  }
}
