/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.util.UUIDUtil;

public abstract class AbstractSessionRepositoryTest {

  private Clock clock;

  protected static final Phonenumber.PhoneNumber PHONE_NUMBER = PhoneNumberUtil.getInstance().getExampleNumber("US");

  protected static final Duration TTL = Duration.ofMinutes(1);

  protected static final SessionMetadata SESSION_METADATA =
      SessionMetadata.newBuilder().setAccountExistsWithE164(true).build();

  protected Clock getClock() {
    return clock;
  }

  protected abstract SessionRepository getRepository();

  @BeforeEach
  protected void setUp() throws Exception {
    clock = mock(Clock.class);
    when(clock.instant()).thenReturn(Instant.now());
  }

  @Test
  void createSession() {
    final Instant currentTime = getClock().instant();
    final RegistrationSession createdSession = getRepository().createSession(PHONE_NUMBER, SESSION_METADATA, currentTime.plus(TTL)).join();

    assertNotNull(createdSession);
    assertNotNull(createdSession.getId());
    assertFalse(createdSession.getId().isEmpty());

    final RegistrationSession expectedSession = RegistrationSession.newBuilder()
        .setId(createdSession.getId())
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .setCreatedEpochMillis(currentTime.toEpochMilli())
        .setExpirationEpochMillis(currentTime.plus(TTL).toEpochMilli())
        .setSessionMetadata(SESSION_METADATA)
        .build();

    assertEquals(expectedSession, createdSession);
  }

  @Test
  void getSession() {
    final SessionRepository repository = getRepository();

    {
      final CompletionException completionException =
          assertThrows(CompletionException.class, () -> repository.getSession(UUID.randomUUID()).join());

      assertInstanceOf(SessionNotFoundException.class, completionException.getCause());
    }

    {
      final RegistrationSession createdSession = repository.createSession(PHONE_NUMBER, SESSION_METADATA, clock.instant().plus(TTL)).join();
      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setId(createdSession.getId())
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setCreatedEpochMillis(clock.instant().toEpochMilli())
          .setExpirationEpochMillis(clock.instant().plus(TTL).toEpochMilli())
          .setSessionMetadata(SESSION_METADATA)
          .build();

      assertEquals(expectedSession, repository.getSession(UUIDUtil.uuidFromByteString(createdSession.getId())).join());
    }
  }

  @Test
  void updateSession() {
    final SessionRepository repository = getRepository();
    final String verificationCode = "123456";
    final Instant expiration = clock.instant().plus(TTL);
    final Instant expirationAfterUpdate = expiration.plusSeconds(17);

    final Function<RegistrationSession, CompletionStage<RegistrationSession>> updateVerifiedCodeFunction =
        session -> CompletableFuture.completedFuture(session.toBuilder()
            .setVerifiedCode(verificationCode)
            .setExpirationEpochMillis(expirationAfterUpdate.toEpochMilli())
            .build());

    {
      final CompletionException completionException =
          assertThrows(CompletionException.class,
              () -> repository.updateSession(UUID.randomUUID(), updateVerifiedCodeFunction).join());

      assertInstanceOf(SessionNotFoundException.class, completionException.getCause());
    }

    {
      final RegistrationSession createdSession = repository.createSession(PHONE_NUMBER, SESSION_METADATA, expiration).join();
      final UUID sessionId = UUIDUtil.uuidFromByteString(createdSession.getId());

      final RegistrationSession updatedSession =
          repository.updateSession(sessionId, updateVerifiedCodeFunction).join();

      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setId(createdSession.getId())
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setVerifiedCode(verificationCode)
          .setCreatedEpochMillis(clock.instant().toEpochMilli())
          .setExpirationEpochMillis(expirationAfterUpdate.toEpochMilli())
          .setSessionMetadata(SESSION_METADATA)
          .build();

      assertEquals(expectedSession, updatedSession);
      assertEquals(expectedSession, repository.getSession(sessionId).join());
    }
  }
}
