package com.stocks.oauth.cimd;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Which resolved addresses a Client ID Metadata Document may be fetched from (§17.9.2, CIMD draft §6.5 SSRF): only
 * globally routable unicast addresses. Loopback, private (10/8, 172.16/12, 192.168/16), CGNAT (100.64/10),
 * link-local, multicast, unspecified, benchmarking and documentation ranges, IPv6 ULA (fc00::/7), site- and
 * link-local IPv6, and IPv4 addresses embedded in IPv6 (mapped, compatible, NAT64, 6to4) that are any of those are
 * refused.
 */
public interface AddressPolicy {

  boolean isAllowed(InetAddress address);

  /** Public addresses only (production). */
  AddressPolicy PUBLIC_ONLY = AddressPolicy::isPublic;

  static boolean isPublic(InetAddress a) {
    if (a == null || a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
        || a.isSiteLocalAddress() || a.isMulticastAddress()) {
      return false;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet4Address) {
      return isPublicV4(b);
    }
    if (a instanceof Inet6Address) {
      return isPublicV6(b);
    }
    return false;
  }

  private static boolean isPublicV4(byte[] b) {
    int o0 = b[0] & 0xff;
    int o1 = b[1] & 0xff;
    int o2 = b[2] & 0xff;
    if (o0 == 0 || o0 == 10 || o0 == 127 || o0 >= 224) {              // this network, private, loopback, multicast, 240/4
      return false;
    }
    if (o0 == 100 && o1 >= 64 && o1 <= 127) {                          // CGNAT 100.64/10
      return false;
    }
    if (o0 == 169 && o1 == 254) {                                      // link-local
      return false;
    }
    if (o0 == 172 && o1 >= 16 && o1 <= 31) {                           // private
      return false;
    }
    if (o0 == 192 && o1 == 168) {                                      // private
      return false;
    }
    if (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) {                // IETF protocol assignments, TEST-NET-1
      return false;
    }
    if (o0 == 198 && (o1 == 18 || o1 == 19)) {                         // benchmarking 198.18/15
      return false;
    }
    if (o0 == 198 && o1 == 51 && o2 == 100) {                          // TEST-NET-2
      return false;
    }
    return !(o0 == 203 && o1 == 0 && o2 == 113);                       // TEST-NET-3
  }

  private static boolean isPublicV6(byte[] b) {
    int first = b[0] & 0xff;
    if ((first & 0xfe) == 0xfc) {                                      // ULA fc00::/7
      return false;
    }
    if (first == 0xfe && (b[1] & 0xc0) == 0x80) {                      // link-local fe80::/10
      return false;
    }
    if (first == 0xff) {                                               // multicast
      return false;
    }
    if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
      return false;                                                    // documentation 2001:db8::/32
    }
    if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
      return embeddedV4(b, 12);                                        // IPv4-mapped ::ffff:a.b.c.d
    }
    if (allZero(b, 0, 12)) {
      return embeddedV4(b, 12);                                        // IPv4-compatible ::a.b.c.d (and ::, ::1)
    }
    if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
        && allZero(b, 4, 12)) {
      return embeddedV4(b, 12);                                        // NAT64 64:ff9b::/96
    }
    if (first == 0x20 && (b[1] & 0xff) == 0x02) {
      return embeddedV4(b, 2);                                         // 6to4 2002::/16
    }
    return true;
  }

  private static boolean embeddedV4(byte[] b, int offset) {
    try {
      InetAddress v4 = InetAddress.getByAddress(new byte[] {b[offset], b[offset + 1], b[offset + 2], b[offset + 3]});
      return isPublic(v4);
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static boolean allZero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }
}
