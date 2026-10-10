package com.stocks.portfolio;

import com.stocks.common.domain.DataQuality;
import java.util.Collection;

/**
 * Orders {@link DataQuality} from best to worst for "the worst quality among the marks used" (§16.1, §21.2):
 * REALTIME_CONSOLIDATED, then REALTIME_SINGLE_VENUE and SIMULATED, DELAYED_15M, SYNTHETIC, STALE, NONE.
 */
public final class Qualities {

  private Qualities() {}

  /** Higher is worse. */
  public static int severity(DataQuality q) {
    if (q == null) {
      return severity(DataQuality.NONE);
    }
    return switch (q) {
      case REALTIME_CONSOLIDATED -> 0;
      case REALTIME_SINGLE_VENUE, SIMULATED -> 1;
      case DELAYED_15M -> 2;
      case SYNTHETIC -> 3;
      case STALE -> 4;
      case NONE -> 5;
    };
  }

  /** The worse of two (the first on a tie). */
  public static DataQuality worse(DataQuality a, DataQuality b) {
    return severity(b) > severity(a) ? b : a;
  }

  /**
   * The worst quality in {@code qualities}; {@code REALTIME_CONSOLIDATED} when there is none (an account with no
   * positions uses no marks, so nothing about its value is degraded).
   */
  public static DataQuality worst(Collection<DataQuality> qualities) {
    DataQuality worst = DataQuality.REALTIME_CONSOLIDATED;
    for (DataQuality q : qualities) {
      worst = worse(worst, q == null ? DataQuality.NONE : q);
    }
    return worst;
  }

  /** STALE or NONE: the value of this mark should not be trusted for money movements (§21.2). */
  public static boolean degraded(DataQuality q) {
    return severity(q) >= severity(DataQuality.STALE);
  }
}
