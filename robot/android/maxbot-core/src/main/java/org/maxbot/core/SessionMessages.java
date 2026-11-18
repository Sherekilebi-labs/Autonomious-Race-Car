package org.maxbot.core;

/** HMAC covers the exact UTF-8 payload, connection identity, direction and monotonic sequence. */
public final class SessionMessages {
  private SessionMessages(){}
  public static String canonical(String direction,String session,long sequence,String payload){return direction+"|"+session+"|"+sequence+"|"+payload;}
  public static boolean verify(String key,String direction,String session,long sequence,String payload,String signature){return sequence>=0&&Protocol.verify(key,canonical(direction,session,sequence,payload),signature);}
  public static String sign(String key,String direction,String session,long sequence,String payload){return Protocol.sign(key,canonical(direction,session,sequence,payload));}
}
