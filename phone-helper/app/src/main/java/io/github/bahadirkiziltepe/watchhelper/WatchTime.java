package io.github.bahadirkiziltepe.watchhelper;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;

/**
 * Encodes the time for the watch's clock (the standard Bluetooth "current time" service).
 *
 * <p>No Android imports on purpose, so this can be checked on a desktop JVM.
 */
final class WatchTime {
  private static final int NANOS_PER_256TH = 1_000_000_000 / 256;
  private static final int SECONDS_PER_QUARTER_HOUR = 15 * 60;

  private WatchTime() {
  }

  /**
   * Ten bytes: year (low byte first), month, day, hour, minute, second, day of week, 1/256 seconds
   * and a reason code. The watch shows exactly the date and time it is given, so this is local time.
   */
  static byte[] currentTime(ZonedDateTime now) {
    int year = now.getYear();
    return new byte[] {(byte) (year & 0xff), (byte) ((year >> 8) & 0xff), (byte) now.getMonthValue(),
        (byte) now.getDayOfMonth(), (byte) now.getHour(), (byte) now.getMinute(), (byte) now.getSecond(),
        (byte) now.getDayOfWeek().getValue(), (byte) (now.getNano() / NANOS_PER_256TH), 0};
  }

  /**
   * Two bytes, both in quarter hours: the zone's normal distance from UTC, and the extra shift
   * while daylight saving time is on. The watch uses them to work out UTC from the local time.
   */
  static byte[] localTime(ZonedDateTime now) {
    ZoneRules rules = now.getZone().getRules();
    Instant instant = now.toInstant();
    int standard = rules.getStandardOffset(instant).getTotalSeconds();
    int total = rules.getOffset(instant).getTotalSeconds();
    return new byte[] {(byte) (standard / SECONDS_PER_QUARTER_HOUR),
        (byte) ((total - standard) / SECONDS_PER_QUARTER_HOUR)};
  }

  /** Seconds between local time and UTC right now. Changes when daylight saving starts or ends. */
  static int offsetSeconds(ZonedDateTime now) {
    return now.getOffset().getTotalSeconds();
  }
}
