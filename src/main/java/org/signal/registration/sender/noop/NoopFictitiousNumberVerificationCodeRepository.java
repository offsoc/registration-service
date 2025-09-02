package org.signal.registration.sender.noop;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.signal.registration.Environments;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeRepository;

@Requires(env = Environments.DEVELOPMENT)
@Requires(missingBeans = FictitiousNumberVerificationCodeRepository.class)
@Singleton
public class NoopFictitiousNumberVerificationCodeRepository implements FictitiousNumberVerificationCodeRepository {

  @Override
  public void storeVerificationCode(final Phonenumber.PhoneNumber phoneNumber, final String verificationCode, final Duration ttl) {
  }
}
