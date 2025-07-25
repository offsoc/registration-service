/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import threegpp.charset.gsm.GSM7BitPackedCharset;
import threegpp.charset.ucs2.UCS2Charset80;

class VerificationSmsBodyLengthTest {

  private static final int SMS_SEGMENT_LENGTH_OCTETS = 140;

  private static final CharsetEncoder GSM7_ENCODER = new GSM7BitPackedCharset().newEncoder();
  private static final CharsetEncoder UCS2_ENCODER = new UCS2Charset80().newEncoder();

  @ParameterizedTest
  @MethodSource
  void messageFitsInSingleSMSSegment(final File propertiesFile, final String key) throws IOException {
    final Properties properties = new Properties();

    try (final FileReader fileReader = new FileReader(propertiesFile)) {
      properties.load(fileReader);
    }

    final String message = properties.getProperty(key)
        .replace("{code}", "123456")
        .replace("{appHash}", "12345678901");

    assertTrue(getEncodedMessageLengthOctets(message) <= SMS_SEGMENT_LENGTH_OCTETS);
  }

  private static int getEncodedMessageLengthOctets(final String message) throws CharacterCodingException {
    if (GSM7_ENCODER.canEncode(message)) {
      return GSM7_ENCODER.encode(CharBuffer.wrap(message)).remaining();
    } else if (UCS2_ENCODER.canEncode(message)) {
      // We subtract 1 here because `threegpp.charset.ucs2.UCS2Charset80` prepends a header byte that's not actually
      // needed for SMS.
      return UCS2_ENCODER.encode(CharBuffer.wrap(message)).remaining() - 1;
    }

    throw new IllegalArgumentException("No telecom encoder encodes message");
  }

  private static Stream<Arguments> messageFitsInSingleSMSSegment() throws URISyntaxException {
    final URL resourceUrl = Objects.requireNonNull(VerificationSmsBodyLengthTest.class.getResource("sms.properties"));
    final File resourceDirectory = Objects.requireNonNull(Paths.get(resourceUrl.toURI()).getParent().toFile());

    //noinspection ConstantConditions
    return Arrays.stream(resourceDirectory.listFiles(file ->
            file.getName().startsWith("sms") && file.getName().endsWith(".properties")))
        .flatMap(file -> {
          final Properties properties = new Properties();

          try (final FileReader fileReader = new FileReader(file)) {
            properties.load(fileReader);
          } catch (final IOException e) {
            throw new UncheckedIOException(e);
          }

          return properties.keySet().stream().map(key -> {
            final String testName = "%s: %s".formatted(
                StringUtils.removeEnd(StringUtils.removeStart(file.getName(), "sms_"), ".properties"),
                key);

            return Arguments.argumentSet(testName, file, key);
          });
        });
  }
}
