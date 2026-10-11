package com.stocks.common.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The key per-IP limits count against (§17.5, §17.9.1): one IPv4 address, or the <b>/64 prefix</b> of an IPv6
 * address. An IPv6 subscriber usually gets a whole /64 (2^64 addresses) and can send every request from a fresh
 * address in it, so keying on the full address would let one client escape every per-IP limit. IPv4-mapped IPv6
 * addresses count as their IPv4 address. Anything that is not an IP literal (a test's "?", a malformed header that a
 * proxy let through) is its own key. Never resolves a name: only literals are parsed.
 */
public final class ClientNetworks {

  /** Characters an IP literal (with an optional zone) can have; anything else is never parsed. */
  private static final Pattern LITERAL = Pattern.compile("\\[?[0-9A-Fa-f:.]{2,45}(%[A-Za-z0-9_.-]{1,16})?]?");

  private ClientNetworks() {}

  /**
   * {@code "2001:db8:1:2::/64"} for any address in that /64, the address itself for IPv4, {@code "?"} for null.
   */
  public static String bucketKey(String ip) {
    if (ip == null || ip.isBlank()) {
      return "?";
    }
    String s = ip.strip();
    if (s.indexOf(':') < 0 || !LITERAL.matcher(s).matches()) {
      return s;
    }
    String literal = s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
    int zone = literal.indexOf('%');
    if (zone >= 0) {
      literal = literal.substring(0, zone);
    }
    if (literal.isEmpty() || (Character.digit(literal.charAt(0), 16) < 0 && literal.charAt(0) != ':')) {
      return s;                       // InetAddress would treat it as a host name: never resolve anything here
    }
    try {
      // an IPv6 literal is parsed, never looked up (InetAddress refuses a malformed one without DNS)
      InetAddress a = InetAddress.getByName(literal);
      if (a instanceof Inet4Address v4) {
        return v4.getHostAddress();
      }
      if (a instanceof Inet6Address v6) {
        return prefix64(v6.getAddress());
      }
    } catch (UnknownHostException | SecurityException e) {
      // not a literal: its own key
    }
    return s;
  }

  /** The first 64 bits as {@code a:b:c:d::/64} (lowercase hex, no leading zeros). */
  static String prefix64(byte[] address) {
    StringBuilder b = new StringBuilder(24);
    for (int i = 0; i < 8; i += 2) {
      int group = ((address[i] & 0xff) << 8) | (address[i + 1] & 0xff);
      b.append(Integer.toHexString(group)).append(':');
    }
    return b.append(":/64").toString();
  }
}
