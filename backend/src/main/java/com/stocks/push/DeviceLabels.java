package com.stocks.push;

import java.util.Locale;

/**
 * A human label for a push device from its {@code User-Agent} ("Safari on iPhone", "Chrome on Android"), shown in
 * Settings so a player can tell their devices apart. Best effort; never contains the raw header.
 */
public final class DeviceLabels {

  public static final int MAX = 80;

  private DeviceLabels() {}

  public static String label(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return "Browser";
    }
    String ua = userAgent.toLowerCase(Locale.ROOT);
    String os = os(ua);
    String browser = browser(ua, os);
    String label = os == null ? browser : browser + " on " + os;
    return label.length() <= MAX ? label : label.substring(0, MAX);
  }

  private static String os(String ua) {
    if (ua.contains("iphone")) {
      return "iPhone";
    }
    if (ua.contains("ipad")) {
      return "iPad";
    }
    if (ua.contains("android")) {
      return "Android";
    }
    if (ua.contains("cros")) {
      return "ChromeOS";
    }
    if (ua.contains("windows")) {
      return "Windows";
    }
    if (ua.contains("mac os x") || ua.contains("macintosh")) {
      return "Mac";
    }
    if (ua.contains("linux")) {
      return "Linux";
    }
    return null;
  }

  private static String browser(String ua, String os) {
    if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) {
      return "Edge";
    }
    if (ua.contains("opr/") || ua.contains("opera")) {
      return "Opera";
    }
    if (ua.contains("samsungbrowser/")) {
      return "Samsung Internet";
    }
    if (ua.contains("firefox/") || ua.contains("fxios/")) {
      return "Firefox";
    }
    if (ua.contains("crios/") || ua.contains("chrome/") || ua.contains("chromium/")) {
      return "Chrome";
    }
    if (ua.contains("safari/") || "iPhone".equals(os) || "iPad".equals(os)) {
      return "Safari";
    }
    return "Browser";
  }
}
