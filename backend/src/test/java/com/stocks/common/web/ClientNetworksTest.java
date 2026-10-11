package com.stocks.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Review finding: per-IP limits keyed on the full address did nothing against an IPv6 client, which can send every
 * request from a fresh address in its /64. The key is now the /64 (IPv4 unchanged), and nothing is ever resolved.
 */
class ClientNetworksTest {

  @Test
  void anIpv6AddressCountsAsItsSlash64() {
    assertThat(ClientNetworks.bucketKey("2001:db8:1:2::1")).isEqualTo("2001:db8:1:2::/64");
    assertThat(ClientNetworks.bucketKey("2001:0db8:0001:0002:ffff:ffff:ffff:fffe")).isEqualTo("2001:db8:1:2::/64");
    assertThat(ClientNetworks.bucketKey("[2001:db8:1:2::abcd]")).isEqualTo("2001:db8:1:2::/64");
    assertThat(ClientNetworks.bucketKey("fe80::1%en0")).isEqualTo("fe80:0:0:0::/64");
    assertThat(ClientNetworks.bucketKey("2001:db8:1:3::1")).isNotEqualTo(ClientNetworks.bucketKey("2001:db8:1:2::1"));
    assertThat(ClientNetworks.bucketKey("::1")).isEqualTo("0:0:0:0::/64");
  }

  @Test
  void ipv4AndMappedAddressesStayWhole() {
    assertThat(ClientNetworks.bucketKey("203.0.113.9")).isEqualTo("203.0.113.9");
    assertThat(ClientNetworks.bucketKey("::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
    assertThat(ClientNetworks.bucketKey(null)).isEqualTo("?");
    assertThat(ClientNetworks.bucketKey(" ")).isEqualTo("?");
  }

  @Test
  void anythingElseIsItsOwnKeyAndNeverLookedUp() {
    assertThat(ClientNetworks.bucketKey("example.com")).isEqualTo("example.com");
    assertThat(ClientNetworks.bucketKey("1.2.3.4:80")).isEqualTo("1.2.3.4:80");
    assertThat(ClientNetworks.bucketKey(".:")).isEqualTo(".:");
    assertThat(ClientNetworks.bucketKey("gg::1")).isEqualTo("gg::1");
    assertThat(ClientNetworks.bucketKey("unknown")).isEqualTo("unknown");
  }
}
