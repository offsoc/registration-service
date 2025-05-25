/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.signal.registration.sender.SenderSelectionStrategy.SenderSelection;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
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
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionMetadata;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.ClientTypes;
import org.signal.registration.util.CompletionExceptions;
import org.signal.registration.util.MessageTransports;
import org.signal.registration.util.UUIDUtil;
import reactor.core.publisher.Mono;

/**
 * The registration service is the core orchestrator of registration business logic and manages registration sessions
 * and verification code sender selection.
 */
@Singleton
public class RegistrationService {

  private final SenderSelectionStrategy senderSelectionStrategy;
  private final SessionRepository sessionRepository;
  private final RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter;
  private final RateLimiter<RegistrationSession> sendSmsVerificationCodePerSessionRateLimiter;
  private final RateLimiter<RegistrationSession> sendVoiceVerificationCodePerSessionRateLimiter;
  private final RateLimiter<RegistrationSession> checkVerificationCodePerSessionRateLimiter;
  private final RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter;
  private final RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter;
  private final RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter;
  private final Clock clock;

  private final Map<String, VerificationCodeSender> sendersByName;

  @VisibleForTesting
  static final Duration SESSION_TTL_AFTER_LAST_ACTION = Duration.ofMinutes(10);


  @VisibleForTesting
  record NextActionTimes(Optional<Instant> nextSms,
                         Optional<Instant> nextVoiceCall,
                         Optional<Instant> nextCodeCheck) {}

  /**
   * Constructs a new registration service that chooses verification code senders with the given strategy and stores
   * session data with the given session repository.
   *
   * @param senderSelectionStrategy                        the strategy to use to choose verification code senders
   * @param sessionRepository                              the repository to use to store session data
   * @param sessionCreationRateLimiter                     a rate limiter that controls the rate at which sessions may
   *                                                       be created for individual phone numbers
   * @param sendSmsVerificationCodePerSessionRateLimiter   a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via SMS for a given session
   * @param sendVoiceVerificationCodePerSessionRateLimiter a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via voice call for a given session
   * @param checkVerificationCodePerSessionRateLimiter     a rate limiter that controls the rate and number of times a
   *                                                       caller may check a verification code for a given session
   * @param sendSmsVerificationCodePerNumberRateLimiter    a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via SMS for a given number
   * @param sendVoiceVerificationCodePerNumberRateLimiter  a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via voice call for a given number
   * @param checkVerificationCodePerNumberRateLimiter      a rate limiter that controls the rate and number of times a
   *                                                       caller may check a verification code for a given number
   * @param verificationCodeSenders                        a list of verification code senders that may be used by this
   *                                                       service
   * @param clock                                          the time source for this registration service
   */
  public RegistrationService(final SenderSelectionStrategy senderSelectionStrategy,
      final SessionRepository sessionRepository,
      @Named("session-creation") final RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter,
      @Named("send-sms-verification-code-per-session") final RateLimiter<RegistrationSession> sendSmsVerificationCodePerSessionRateLimiter,
      @Named("send-voice-verification-code-per-session") final RateLimiter<RegistrationSession> sendVoiceVerificationCodePerSessionRateLimiter,
      @Named("check-verification-code-per-session") final RateLimiter<RegistrationSession> checkVerificationCodePerSessionRateLimiter,
      @Named("send-sms-verification-code-per-number") final RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter,
      @Named("send-voice-verification-code-per-number") final RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter,
      @Named("check-verification-code-per-number") final RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter,
      final List<VerificationCodeSender> verificationCodeSenders,
      final Clock clock) {

    this.senderSelectionStrategy = senderSelectionStrategy;
    this.sessionRepository = sessionRepository;
    this.sessionCreationRateLimiter = sessionCreationRateLimiter;
    this.sendSmsVerificationCodePerSessionRateLimiter = sendSmsVerificationCodePerSessionRateLimiter;
    this.sendVoiceVerificationCodePerSessionRateLimiter = sendVoiceVerificationCodePerSessionRateLimiter;
    this.checkVerificationCodePerSessionRateLimiter = checkVerificationCodePerSessionRateLimiter;
    this.sendSmsVerificationCodePerNumberRateLimiter = sendSmsVerificationCodePerNumberRateLimiter;
    this.sendVoiceVerificationCodePerNumberRateLimiter = sendVoiceVerificationCodePerNumberRateLimiter;
    this.checkVerificationCodePerNumberRateLimiter = checkVerificationCodePerNumberRateLimiter;
    this.clock = clock;

    this.sendersByName = verificationCodeSenders.stream()
        .collect(Collectors.toMap(VerificationCodeSender::getName, Function.identity()));
  }

  /**
   * Creates a new registration session for the given phone number.
   *
   * @param phoneNumber the phone number for which to create a new registration session
   *
   * @return a future that yields the newly-created registration session once the session has been created and stored in
   * this service's session repository; the returned future may fail with a
   * {@link org.signal.registration.ratelimit.RateLimitExceededException}
   */
  public CompletableFuture<RegistrationSession> createRegistrationSession(final Phonenumber.PhoneNumber phoneNumber,
      final String rateLimitCollationKey, final SessionMetadata sessionMetadata) {

    return sessionCreationRateLimiter.checkRateLimit(Pair.of(phoneNumber, rateLimitCollationKey))
        .thenCompose(ignored ->
            sessionRepository.createSession(phoneNumber, sessionMetadata, clock.instant().plus(SESSION_TTL_AFTER_LAST_ACTION)));
  }

  /**
   * Retrieves a registration session by its unique identifier.
   *
   * @param sessionId the unique identifier for the session to retrieve
   *
   * @return a future that yields the identified session when complete; the returned future may fail with a
   * {@link org.signal.registration.session.SessionNotFoundException}
   */
  public CompletableFuture<RegistrationSession> getRegistrationSession(final UUID sessionId) {
    return sessionRepository.getSession(sessionId);
  }

  /**
   * Selects a verification code sender for the destination phone number associated with the given session and sends a
   * verification code.
   *
   * @param messageTransport the transport via which to send a verification code to the destination phone number
   * @param sessionId the session within which to send (or re-send) a verification code
   * @param senderName if specified, a preferred sender to use
   * @param languageRanges a prioritized list of languages in which to send the verification code
   * @param clientType the type of client receiving the verification code
   *
   * @return a future that yields the updated registration session when the verification code has been sent and updates
   * to the session have been stored
   */
  public CompletableFuture<RegistrationSession> sendVerificationCode(final MessageTransport messageTransport,
      final UUID sessionId,
      @Nullable final String senderName,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    final RateLimiter<RegistrationSession> sessionRateLimiter = switch (messageTransport) {
      case SMS -> sendSmsVerificationCodePerSessionRateLimiter;
      case VOICE -> sendVoiceVerificationCodePerSessionRateLimiter;
    };
    final RateLimiter<Phonenumber.PhoneNumber> numberRateLimiter = switch (messageTransport) {
      case SMS -> sendSmsVerificationCodePerNumberRateLimiter;
      case VOICE -> sendVoiceVerificationCodePerNumberRateLimiter;
    };

    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          if (StringUtils.isNotBlank(session.getVerifiedCode())) {
            return CompletableFuture.failedFuture(new SessionAlreadyVerifiedException(session));
          }

          return sessionRateLimiter.checkRateLimit(session)
              .thenCompose(ignored -> numberRateLimiter.checkRateLimit(getPhoneNumberFromSession(session))
                  .exceptionallyCompose(addSessionToRateLimitExceededExceptionIfNecessary(session)))
              .thenApply(ignored -> session);
        })
        .thenCompose(session -> {

          final Set<String> previouslyFailedSenders = session.getRegistrationAttemptsList()
              .stream()
              .map(RegistrationAttempt::getSenderName)
              .collect(Collectors.toSet());

          // Add senders from failed attempts with a "provider unavailable" error
          session.getFailedAttemptsList()
              .stream()
              .filter(attempt -> attempt.getFailedSendReason() == FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
              .map(FailedSendAttempt::getSenderName)
              .forEach(previouslyFailedSenders::add);

          final Phonenumber.PhoneNumber phoneNumberFromSession = getPhoneNumberFromSession(session);
          final SenderSelection selection = senderSelectionStrategy.chooseVerificationCodeSender(
              messageTransport, phoneNumberFromSession, languageRanges, clientType, senderName,
              previouslyFailedSenders);

          return selection.sender()
              .sendVerificationCode(messageTransport, phoneNumberFromSession, languageRanges, clientType)
              .thenCompose(attemptData -> sessionRepository.updateSession(sessionId, sessionToUpdate -> {
                final RegistrationSession.Builder builder = sessionToUpdate.toBuilder()
                    .setCheckCodeAttempts(0)
                    .setLastCheckCodeAttemptEpochMillis(0)
                    .addRegistrationAttempts(buildRegistrationAttempt(selection,
                        messageTransport,
                        clientType,
                        attemptData,
                        selection.sender().getAttemptTtl()));

                return getSessionExpiration(builder.build())
                    .thenApply(expiration -> builder.setExpirationEpochMillis(expiration.toEpochMilli()).build());
              }))
              .exceptionallyCompose(throwable -> {
                final Throwable unwrapped = CompletionExceptions.unwrap(throwable);
                if (unwrapped instanceof SenderRejectedTransportException e) {
                  return sessionRepository.updateSession(sessionId, sessionToUpdate -> {
                        final RegistrationSession.Builder builder = sessionToUpdate.toBuilder()
                            .setCheckCodeAttempts(0)
                            .setLastCheckCodeAttemptEpochMillis(0)
                            .addRejectedTransports(
                                MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport));

                        return getSessionExpiration(builder.build())
                            .thenApply(expiration -> builder.setExpirationEpochMillis(expiration.toEpochMilli()).build());
                      })
                      .thenApply(updatedSession -> {
                        throw CompletionExceptions.wrap(new TransportNotAllowedException(e, updatedSession));
                      });
                }

                final FailedSendReason failedSendReason;
                if (unwrapped instanceof SenderFraudBlockException) {
                  failedSendReason = FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD;
                } else if (unwrapped instanceof SenderRejectedRequestException) {
                  failedSendReason = FailedSendReason.FAILED_SEND_REASON_REJECTED;
                } else {
                  failedSendReason = FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE;
                }

                return sessionRepository.updateSession(sessionId, sessionToUpdate ->
                        CompletableFuture.completedFuture(sessionToUpdate.toBuilder()
                            .addFailedAttempts(FailedSendAttempt.newBuilder()
                                .setTimestampEpochMillis(clock.instant().toEpochMilli())
                                .setSenderName(selection.sender().getName())
                                .setSelectionReason(selection.reason().toString())
                                .setMessageTransport(
                                    MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport))
                                .setClientType(ClientTypes.getRpcClientTypeFromSenderClientType(clientType))
                                .setFailedSendReason(failedSendReason))
                            .build()))
                    .thenApply(updatedSession -> {
                      if (unwrapped instanceof RateLimitExceededException e) {
                        throw CompletionExceptions.wrap(
                            new RateLimitExceededException(e.getRetryAfterDuration().orElse(null),
                                e.getRegistrationSession().orElse(updatedSession)));
                      }
                      throw CompletionExceptions.wrap(throwable);
                    });
              });
        });
  }

  private RegistrationAttempt buildRegistrationAttempt(final SenderSelection selection,
      final MessageTransport messageTransport,
      final ClientType clientType,
      final AttemptData attemptData,
      final Duration ttl) {

    final Instant currentTime = clock.instant();

    final RegistrationAttempt.Builder registrationAttemptBuilder = RegistrationAttempt.newBuilder()
        .setTimestampEpochMillis(currentTime.toEpochMilli())
        .setExpirationEpochMillis(currentTime.plus(ttl).toEpochMilli())
        .setSenderName(selection.sender().getName())
        .setSelectionReason(selection.reason().toString())
        .setMessageTransport(MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport))
        .setClientType(ClientTypes.getRpcClientTypeFromSenderClientType(clientType))
        .setSenderData(ByteString.copyFrom(attemptData.senderData()));

    attemptData.remoteId().ifPresent(registrationAttemptBuilder::setRemoteId);

    return registrationAttemptBuilder.build();
  }

  /**
   * Checks whether a client-provided verification code matches the expected verification code for a given registration
   * session. The code may be verified by communicating with an external service, by checking stored session data, or
   * by comparing against a previously-accepted verification code for the same session (i.e. in the case of retried
   * requests due to an interrupted connection).
   *
   * @param sessionId an identifier for a registration session against which to check a verification code
   * @param verificationCode a client-provided verification code
   *
   * @return a future that yields the updated registration; the session's {@code verifiedCode} field will be set if the
   * session has been successfully verified
   */
  public CompletableFuture<RegistrationSession> checkVerificationCode(final UUID sessionId, final String verificationCode) {
    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          // If a connection was interrupted, a caller may repeat a verification request. Check to see if we already
          // have a known verification code for this session and, if so, check the provided code against that code
          // instead of making a call upstream.
          if (StringUtils.isNotBlank(session.getVerifiedCode())) {
            return CompletableFuture.completedFuture(session);
          } else {
            return checkVerificationCode(session, verificationCode);
          }
        });
  }

  private CompletableFuture<RegistrationSession> checkVerificationCode(final RegistrationSession session, final String verificationCode) {
    if (session.getRegistrationAttemptsCount() == 0) {
      return CompletableFuture.failedFuture(new NoVerificationCodeSentException(session));
    } else {

      return checkVerificationCodePerSessionRateLimiter.checkRateLimit(session)
          .thenCompose(ignored -> checkVerificationCodePerNumberRateLimiter.checkRateLimit(getPhoneNumberFromSession(session)))
          .exceptionallyCompose(addSessionToRateLimitExceededExceptionIfNecessary(session))
          .thenCompose(ignored -> {
        final RegistrationAttempt currentRegistrationAttempt =
            session.getRegistrationAttempts(session.getRegistrationAttemptsCount() - 1);

        if (Instant.ofEpochMilli(currentRegistrationAttempt.getExpirationEpochMillis()).isBefore(clock.instant())) {
          return CompletableFuture.failedFuture(new AttemptExpiredException());
        }

        final VerificationCodeSender sender = sendersByName.get(currentRegistrationAttempt.getSenderName());

        if (sender == null) {
          throw new IllegalArgumentException("Unrecognized sender: " + currentRegistrationAttempt.getSenderName());
        }

        return sender.checkVerificationCode(verificationCode, currentRegistrationAttempt.getSenderData().toByteArray())
            .exceptionally(throwable -> {
              // The sender may view the submitted code as an illegal argument or may reject the attempt to check a
              // code altogether. We can treat any case of "the sender got it, but said 'no'" the same way we would
              // treat an accepted-but-incorrect code.
              if (CompletionExceptions.unwrap(throwable) instanceof SenderRejectedRequestException) {
                return false;
              }

              throw CompletionExceptions.wrap(throwable);
            })
            .thenCompose(verified -> recordCheckVerificationCodeAttempt(session, verified ? verificationCode : null));
      });
    }
  }

  private CompletableFuture<RegistrationSession> recordCheckVerificationCodeAttempt(final RegistrationSession session,
      @Nullable final String verifiedCode) {

    return sessionRepository.updateSession(UUIDUtil.uuidFromByteString(session.getId()), s -> {
      final RegistrationSession.Builder builder = s.toBuilder()
          .setCheckCodeAttempts(session.getCheckCodeAttempts() + 1)
          .setLastCheckCodeAttemptEpochMillis(clock.millis());

      if (verifiedCode != null) {
        builder.setVerifiedCode(verifiedCode);
      }

      return getSessionExpiration(builder.build()).thenApply(expiration ->
          builder.setExpirationEpochMillis(expiration.toEpochMilli()).build()
      );
    });
  }

  /**
   * Interprets a raw {@code RegistrationSession} and produces {@link RegistrationSessionMetadata} suitable for
   * presentation to remote callers.
   *
   * @param session the session to interpret
   *
   * @return session metadata suitable for presentation to remote callers
   */
  public CompletionStage<RegistrationSessionMetadata> buildSessionMetadata(final RegistrationSession session) {
    final boolean verified = StringUtils.isNotBlank(session.getVerifiedCode());

    final CompletionStage<RegistrationSessionMetadata.Builder> sessionMetadataBuilderFuture = getSessionExpiration(session)
        .thenApply(expiration -> RegistrationSessionMetadata.newBuilder()
            .setSessionId(session.getId())
            .setE164(Long.parseLong(StringUtils.removeStart(session.getPhoneNumber(), "+")))
            .setVerified(verified)
            .setExpirationSeconds(Duration.between(clock.instant(), expiration).getSeconds()));

    final Instant currentTime = clock.instant();
    return getNextActionTimes(session)
        .thenCombine(sessionMetadataBuilderFuture, (nextActionTimes, sessionMetadataBuilder) -> {

          nextActionTimes.nextSms().ifPresent(nextAction -> {
            sessionMetadataBuilder.setMayRequestSms(true);
            sessionMetadataBuilder.setNextSmsSeconds(
                nextAction.isBefore(currentTime) ? 0 : Duration.between(currentTime, nextAction).toSeconds());
          });

          nextActionTimes.nextVoiceCall().ifPresent(nextAction -> {
            sessionMetadataBuilder.setMayRequestVoiceCall(true);
            sessionMetadataBuilder.setNextVoiceCallSeconds(
                nextAction.isBefore(currentTime) ? 0 : Duration.between(currentTime, nextAction).toSeconds());
          });

          nextActionTimes.nextCodeCheck().ifPresent(nextAction -> {
            sessionMetadataBuilder.setMayCheckCode(true);
            sessionMetadataBuilder.setNextCodeCheckSeconds(
                nextAction.isBefore(currentTime) ? 0 : Duration.between(currentTime, nextAction).toSeconds());
          });

          return sessionMetadataBuilder.build();
        });
  }

  @VisibleForTesting
  CompletionStage<Instant> getSessionExpiration(final RegistrationSession session) {
    final CompletionStage<Instant> expiration;

    if (StringUtils.isBlank(session.getVerifiedCode())) {
      final List<Instant> candidateExpirations = new ArrayList<>(session.getRegistrationAttemptsList().stream()
          .map(attempt -> Instant.ofEpochMilli(attempt.getExpirationEpochMillis()))
          .toList());

      expiration = getNextActionTimes(session).thenApply(nextActionTimes -> {
        nextActionTimes.nextSms()
            .map(nextAction -> nextAction.plus(SESSION_TTL_AFTER_LAST_ACTION))
            .ifPresent(candidateExpirations::add);

        nextActionTimes.nextVoiceCall()
            .map(nextAction -> nextAction.plus(SESSION_TTL_AFTER_LAST_ACTION))
            .ifPresent(candidateExpirations::add);

        nextActionTimes.nextCodeCheck()
            .map(nextAction -> nextAction.plus(SESSION_TTL_AFTER_LAST_ACTION))
            .ifPresent(candidateExpirations::add);

        // If a session never has a successful registration attempt and exhausts all SMS and voice ratelimits,
        // fall back to the expiration set at session creation time
        return candidateExpirations.stream().max(Comparator.naturalOrder())
            .orElse(Instant.ofEpochMilli(session.getExpirationEpochMillis()));
      });

    } else {
      // The session must have been verified as a result of the last check
      expiration = CompletableFuture.completedFuture(
          Instant.ofEpochMilli(session.getLastCheckCodeAttemptEpochMillis()).plus(SESSION_TTL_AFTER_LAST_ACTION));
    }

    return expiration;
  }

  @VisibleForTesting
  CompletionStage<NextActionTimes> getNextActionTimes(final RegistrationSession session) {
    final boolean verified = StringUtils.isNotBlank(session.getVerifiedCode());

    Mono<Optional<Instant>> nextSms = Mono.just(Optional.empty());
    Mono<Optional<Instant>> nextVoiceCall = Mono.just(Optional.empty());
    Mono<Optional<Instant>> nextCodeCheck = Mono.just(Optional.empty());

    // If the session is already verified, callers can't request or check more verification codes
    if (!verified) {

      // Callers can only check codes if there's an active attempt
      if (session.getRegistrationAttemptsCount() > 0) {
        final Instant currentAttemptExpiration = Instant.ofEpochMilli(
            session.getRegistrationAttemptsList().get(session.getRegistrationAttemptsCount() - 1)
                .getExpirationEpochMillis());

        if (!clock.instant().isAfter(currentAttemptExpiration)) {
          nextCodeCheck = Mono.fromFuture(checkVerificationCodePerSessionRateLimiter.getTimeOfNextAction(session));
        }
      }

      // Callers can't request more verification codes if they've exhausted their check attempts (since they can't check
      // any new codes they might receive)
      nextSms = Mono.fromFuture(sendSmsVerificationCodePerSessionRateLimiter.getTimeOfNextAction(session));

      // Callers may not request codes via phone call until they've attempted an SMS
      final boolean hasAttemptedSms = session.getRegistrationAttemptsList().stream().anyMatch(attempt ->
          attempt.getMessageTransport() == org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS) ||
          session.getRejectedTransportsList().contains(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS) ||
              session.getFailedAttemptsList().stream().anyMatch(attempt ->
                  attempt.getMessageTransport() == org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS
                      && attempt.getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD);

      if (hasAttemptedSms) {
        nextVoiceCall = Mono.fromFuture(sendVoiceVerificationCodePerSessionRateLimiter.getTimeOfNextAction(session));
      }
    }

    return Mono.zip(nextSms, nextVoiceCall, nextCodeCheck)
        .map(zipped -> new NextActionTimes(zipped.getT1(), zipped.getT2(), zipped.getT3()))
        .toFuture();
  }

  private Phonenumber.PhoneNumber getPhoneNumberFromSession(final RegistrationSession session) throws CompletionException {
    try {
      return PhoneNumberUtil.getInstance().parse(session.getPhoneNumber(), null);
    } catch (final NumberParseException e) {
      // This should never happen because we're parsing a phone number from the session, which means we've
      // parsed it successfully in the past
      throw new CompletionException(e);
    }
  }

  private Function<Throwable, CompletionStage<Void>> addSessionToRateLimitExceededExceptionIfNecessary(
      final RegistrationSession session) {
    return throwable -> {
      Throwable unwrapped = CompletionExceptions.unwrap(throwable);
      if (unwrapped instanceof RateLimitExceededException e) {
        // the number-keyed limiter doesn't know about the session, so enrich the exception, if necessary
        if (e.getRegistrationSession().isEmpty()) {
          unwrapped = new RateLimitExceededException(e.getRetryAfterDuration().orElse(null), session);
        }
      }
      return CompletableFuture.failedFuture(unwrapped);
    };
  }
}
