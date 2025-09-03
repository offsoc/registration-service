/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import com.messagebird.MessageBirdClient;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.objects.MessageResponse;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.Clock;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.sender.messagebird.classic.MessageBirdVoiceSender;

/**
 * Analyzes verification attempts from {@link MessageBirdVoiceSender}.
 */
@Singleton
class MessageBirdVoiceAttemptAnalyzer extends AbstractMessageBirdAttemptAnalyzer {

  private final MessageBirdClient messageBirdClient;

  protected MessageBirdVoiceAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock,
      final MessageBirdPriceEstimator messageBirdPriceEstimator,
      final MessageBirdClient messageBirdClient) {

    super(repository, attemptAnalyzedEventPublisher, clock, messageBirdPriceEstimator);

    this.messageBirdClient = messageBirdClient;
  }

  @Override
  @Scheduled(fixedDelay = "${analytics.messagebird.voice.analysis-interval:4h}")
  protected void analyzeAttempts() {
    super.analyzeAttempts();
  }

  @Override
  protected String getSenderName() {
    return MessageBirdVoiceSender.SENDER_NAME;
  }

  @Override
  protected MessageResponse.Recipients getRecipients(final AttemptPendingAnalysis attemptPendingAnalysis)
      throws MessageBirdException {

    return messageBirdClient.viewVoiceMessage(attemptPendingAnalysis.getRemoteId()).getRecipients();
  }
}
