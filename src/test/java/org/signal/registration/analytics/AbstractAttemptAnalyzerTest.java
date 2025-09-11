/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.TaskScheduler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

class AbstractAttemptAnalyzerTest {

  private AttemptPendingAnalysisRepository repository;
  private ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher;
  private Clock clock;

  private TestAttemptAnalyzer attemptAnalyzer;

  private static final String TEST_SENDER_NAME = "test";

  private static class TestAttemptAnalyzer extends AbstractAttemptAnalyzer {

    private Function<AttemptPendingAnalysis, AttemptAnalysis> attemptAnalysisFunction;

    protected TestAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
        final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
        final Clock clock) {

      super(repository, Schedulers.immediate(), attemptAnalyzedEventPublisher, clock);
    }

    @Override
    protected String getSenderName() {
      return TEST_SENDER_NAME;
    }

    @Override
    protected AttemptAnalysis analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis) {
      return attemptAnalysisFunction.apply(attemptPendingAnalysis);
    }

    public void setAnalysisFunction(final Function<AttemptPendingAnalysis, AttemptAnalysis> attemptAnalysisFunction) {
      this.attemptAnalysisFunction = attemptAnalysisFunction;
    }
  }

  @BeforeEach
  void setUp() {
    repository = mock(AttemptPendingAnalysisRepository.class);

    //noinspection unchecked
    attemptAnalyzedEventPublisher = mock(ApplicationEventPublisher.class);
    clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());

    final TaskScheduler taskScheduler = mock(TaskScheduler.class);

    //noinspection unchecked
    when(taskScheduler.scheduleWithFixedDelay(any(), any(), any())).thenReturn(mock(ScheduledFuture.class));

    attemptAnalyzer = new TestAttemptAnalyzer(repository, attemptAnalyzedEventPublisher, clock);
  }

  @Test
  void analyzeAttempts() {
    final String remoteId = RandomStringUtils.insecure().nextAlphabetic(16);

    final AttemptPendingAnalysis attemptPendingAnalysis = AttemptPendingAnalysis.newBuilder()
        .setSenderName(TEST_SENDER_NAME)
        .setRemoteId(remoteId)
        .setTimestampEpochMillis(clock.millis())
        .build();

    final AttemptAnalysis attemptAnalysis = new AttemptAnalysis(
        Optional.of(new Money(new BigDecimal("0.1"), Currency.getInstance("USD"))),
        Optional.of(new Money(new BigDecimal("0.15"), Currency.getInstance("CAD"))),
        Optional.of("001"),
        Optional.of("002"));

    when(repository.getBySender(TEST_SENDER_NAME)).thenReturn(Stream.of(attemptPendingAnalysis));
    attemptAnalyzer.setAnalysisFunction(ignored -> attemptAnalysis);

    attemptAnalyzer.analyzeAttempts();

    verify(repository).remove(attemptPendingAnalysis);
    verify(attemptAnalyzedEventPublisher).publishEvent(new AttemptAnalyzedEvent(attemptPendingAnalysis, attemptAnalysis));
  }

  @Test
  void analyzeAttemptsNotAvailable() {
    final AttemptPendingAnalysis attemptPendingAnalysis = AttemptPendingAnalysis.newBuilder()
        .setSenderName(TEST_SENDER_NAME)
        .setRemoteId(RandomStringUtils.insecure().nextAlphabetic(16))
        .setTimestampEpochMillis(clock.millis())
        .build();

    when(repository.getBySender(TEST_SENDER_NAME)).thenReturn(Stream.of(attemptPendingAnalysis));
    attemptAnalyzer.setAnalysisFunction(ignored -> AttemptAnalysis.EMPTY);

    attemptAnalyzer.analyzeAttempts();

    verify(repository, never()).remove(any());
    verify(attemptAnalyzedEventPublisher, never()).publishEvent(any());
  }

  @Test
  void analyzeAttemptsNotAvailableDeadlinePassed() {
    final String remoteId = RandomStringUtils.insecure().nextAlphabetic(16);

    final AttemptPendingAnalysis attemptPendingAnalysis = AttemptPendingAnalysis.newBuilder()
        .setSenderName(TEST_SENDER_NAME)
        .setRemoteId(remoteId)
        .setTimestampEpochMillis(clock.instant().minus(AbstractAttemptAnalyzer.DEFAULT_PRICING_DEADLINE).minusSeconds(1).toEpochMilli())
        .build();

    when(repository.getBySender(TEST_SENDER_NAME)).thenReturn(Stream.of(attemptPendingAnalysis));
    attemptAnalyzer.setAnalysisFunction(ignored -> AttemptAnalysis.EMPTY);

    attemptAnalyzer.analyzeAttempts();

    verify(repository).remove(attemptPendingAnalysis);
    verify(attemptAnalyzedEventPublisher).publishEvent(new AttemptAnalyzedEvent(attemptPendingAnalysis, AttemptAnalysis.EMPTY));
  }

  @Test
  void analyzeAttemptsError() {
    final AttemptPendingAnalysis attemptPendingAnalysis = AttemptPendingAnalysis.newBuilder()
        .setSenderName(TEST_SENDER_NAME)
        .setRemoteId(RandomStringUtils.insecure().nextAlphabetic(16))
        .setTimestampEpochMillis(clock.millis())
        .build();

    when(repository.getBySender(TEST_SENDER_NAME)).thenReturn(Stream.of(attemptPendingAnalysis));
    attemptAnalyzer.setAnalysisFunction(ignored -> {
      throw new UncheckedIOException(new IOException("OH NO"));
    });

    attemptAnalyzer.analyzeAttempts();

    verify(repository, never()).remove(any());
    verify(attemptAnalyzedEventPublisher, never()).publishEvent(any());
  }
}
