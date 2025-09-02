package org.signal.registration.sender.messagebird.classic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.messagebird.MessageBirdClient;
import com.messagebird.exceptions.GeneralException;
import com.messagebird.exceptions.UnauthorizedException;
import com.messagebird.objects.Message;
import com.messagebird.objects.MessageResponse;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationSmsBodyProvider;
import org.signal.registration.sender.messagebird.MessageBirdSenderConfiguration;

public class MessageBirdSmsSenderTest {

  private static final Phonenumber.PhoneNumber NUMBER = PhoneNumberUtil.getInstance().getExampleNumber("US");
  private static final String E164 = PhoneNumberUtil.getInstance()
      .format(NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164);
  private static final List<Locale.LanguageRange> EN = Locale.LanguageRange.parse("en");
  private static final String CODE = "12345";
  private static final String BODY = "body";


  private VerificationCodeGenerator codeGenerator;
  private VerificationSmsBodyProvider bodyProvider;
  private MessageBirdClient client;
  private MessageBirdSmsSender sender;


  @BeforeEach
  public void setup() {
    final MessageBirdSmsConfiguration config = new MessageBirdSmsConfiguration(Duration.ofSeconds(1));
    codeGenerator = mock(VerificationCodeGenerator.class);
    bodyProvider = mock(VerificationSmsBodyProvider.class);
    client = mock(MessageBirdClient.class);

    when(codeGenerator.generateVerificationCode()).thenReturn(CODE);
    when(bodyProvider.getVerificationBody(NUMBER, ClientType.IOS, CODE, EN)).thenReturn(BODY);

    sender = new MessageBirdSmsSender(config, codeGenerator, bodyProvider, client, mock(ApiClientInstrumenter.class),
        new MessageBirdSenderConfiguration("test", Collections.emptyMap()));
  }

  private static MessageResponse response(int failedDeliveryCount) {
    MessageResponse.Recipients recipients = mock(MessageResponse.Recipients.class);
    when(recipients.getTotalDeliveryFailedCount()).thenReturn(failedDeliveryCount);
    final MessageResponse response = mock(MessageResponse.class);
    when(response.getRecipients()).thenReturn(recipients);
    when(response.getId()).thenReturn(RandomStringUtils.insecure().nextAlphabetic(16));
    return response;
  }

  @Test
  public void failedSend() throws GeneralException, UnauthorizedException {
    final MessageResponse response = response(1);
    when(client.sendMessage(argThat((Message message) ->
        message.getBody().equals(BODY) && message.getRecipients().equals(E164))))
        .thenReturn(response);
    assertThrows(SenderRejectedRequestException.class, () -> sender
        .sendVerificationCode(MessageTransport.SMS, NUMBER, EN, ClientType.IOS));
  }

  @Test
  public void errorSend() throws GeneralException, UnauthorizedException {
    when(client.sendMessage(argThat((Message message) ->
        message.getBody().equals(BODY) && message.getRecipients().equals(E164))))
        .thenThrow(new GeneralException("test"));

    final UncheckedIOException ioException = assertThrows(UncheckedIOException.class,
        () -> sender.sendVerificationCode(MessageTransport.SMS, NUMBER, EN, ClientType.IOS));

    assertInstanceOf(GeneralException.class, ioException.getCause().getCause());
  }

  @Test
  public void sendAndVerify()
      throws GeneralException, UnauthorizedException, SenderRejectedRequestException {
    when(codeGenerator.generateVerificationCode()).thenReturn("12345");
    when(bodyProvider.getVerificationBody(NUMBER, ClientType.IOS, "12345", EN)).thenReturn("body");

    final MessageResponse response = response(0);
    when(client.sendMessage(argThat((Message message) ->
        message.getBody().equals("body") && message.getRecipients().equals(E164))))
        .thenReturn(response);

    final byte[] senderData = sender
        .sendVerificationCode(MessageTransport.SMS, NUMBER, Locale.LanguageRange.parse("en"), ClientType.IOS)
        .senderData();

    assertFalse(sender.checkVerificationCode("123456", senderData));
    assertTrue(sender.checkVerificationCode("12345", senderData));
  }

}
