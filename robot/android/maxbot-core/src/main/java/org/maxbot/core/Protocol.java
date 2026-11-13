package org.maxbot.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Canonical integer wire fields are shared with firmware/maxbot and the protocol tests. */
public final class Protocol {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static final class Signer {
    final Mac mac;
    String key;

    Signer() {
      try {
        mac = Mac.getInstance("HmacSHA256");
      } catch (java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static final ThreadLocal<Signer> SIGNERS = ThreadLocal.withInitial(Signer::new);

  private Protocol() {}

  public static String canonical(
      String boot,
      String session,
      long seq,
      long issued,
      int ttl,
      String mode,
      int steering,
      int pwm) {
    return "1|" + boot + "|" + session + "|" + seq + "|" + issued + "|" + ttl + "|" + mode + "|"
        + steering + "|" + pwm;
  }

  public static String motion(String boot,String session,long epoch,long seq,long issued,long expires,String mode,int angle,int pwm,String state,String frame,String calibration) {
    return "2|"+boot+"|"+session+"|"+epoch+"|"+seq+"|"+issued+"|"+expires+"|"+mode+"|"+angle+"|"+pwm+"|"+state+"|"+frame+"|"+calibration;
  }

  public static String sign(String key, String message) {
    try {
      Signer signer = SIGNERS.get();
      if (!key.equals(signer.key)) {
        signer.mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        signer.key = key;
      }
      byte[] bytes = signer.mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      char[] hex = new char[64];
      for (int i = 0; i < bytes.length; i++) {
        int b = bytes[i] & 255;
        hex[2 * i] = HEX[b >>> 4];
        hex[2 * i + 1] = HEX[b & 15];
      }
      return new String(hex);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean verify(String key, String message, String signature) {
    return signature != null
        && MessageDigest.isEqual(
            sign(key, message).getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII));
  }
}
