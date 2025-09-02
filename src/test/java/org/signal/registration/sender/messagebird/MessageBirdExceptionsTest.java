/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.messagebird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.messagebird.exceptions.GeneralException;
import com.messagebird.objects.ErrorReport;
import io.micronaut.http.HttpStatus;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.sender.SenderRejectedRequestException;

public class MessageBirdExceptionsTest {

  private static Stream<Arguments> selectException() {
    return Stream.of(
        Arguments.of(List.of(), HttpStatus.TOO_MANY_REQUESTS, SenderRejectedRequestException.class),
        Arguments.of(List.of(9999), HttpStatus.TOO_MANY_REQUESTS, SenderRejectedRequestException.class),
        Arguments.of(List.of(9), HttpStatus.OK, SenderRejectedRequestException.class),
        Arguments.of(List.of(10), HttpStatus.OK, SenderRejectedRequestException.class),
        Arguments.of(List.of(9, 10), HttpStatus.OK, SenderRejectedRequestException.class),
        Arguments.of(List.of(2), HttpStatus.OK, SenderRejectedRequestException.class),
        Arguments.of(List.of(2, 9), HttpStatus.OK, SenderRejectedRequestException.class),
        Arguments.of(List.of(9), HttpStatus.TOO_MANY_REQUESTS, SenderRejectedRequestException.class),
        Arguments.of(List.of(), HttpStatus.I_AM_A_TEAPOT, null)
    );
  }

  @ParameterizedTest
  @MethodSource
  public void selectException(final List<Integer> messageBirdErrors,
      final HttpStatus status,
      @Nullable final Class<? extends Exception> expectedType) {

    final GeneralException ex = mock(GeneralException.class);
    when(ex.getErrors())
        .thenReturn(messageBirdErrors
            .stream()
            .map(i -> new ErrorReport(i, "", "", ""))
            .toList());

    when(ex.getResponseCode()).thenReturn(status.getCode());

    assertEquals(expectedType, MessageBirdExceptions.toSenderRejectedException(ex)
        .map(SenderRejectedRequestException::getClass)
        .orElse(null));
  }

}
