package io.github.bahadirkiziltepe.watchhelper;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The words for one call of the phone's call history, as the watch shows them under the name:
 * whether the call was made, received, missed or declined, and when.
 *
 * <p>No Android imports on purpose, so this can be checked on a desktop JVM.
 */
final class CallRows {
  // What Android's call log calls the kinds of calls (CallLog.Calls.*_TYPE).
  static final int INCOMING = 1;
  static final int OUTGOING = 2;
  static final int MISSED = 3;
  static final int VOICEMAIL = 4;
  static final int REJECTED = 5;
  static final int BLOCKED = 6;
  static final int ANSWERED_ELSEWHERE = 7;

  private static final String[] DAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
  private static final String[] MONTHS =
      {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

  private CallRows() {
  }

  /** True for the entries of the call log that are calls with a person: not voicemail, not blocked. */
  static boolean shown(int type) {
    return type != VOICEMAIL && type != BLOCKED;
  }

  /** True if somebody called the phone, whatever became of the call. */
  static boolean incoming(int type) {
    return type == INCOMING || type == MISSED || type == REJECTED || type == ANSWERED_ELSEWHERE;
  }

  static boolean missed(int type) {
    return type == MISSED;
  }

  static String kind(int type) {
    switch (type) {
      case OUTGOING:
        return "Made";
      case INCOMING:
      case ANSWERED_ELSEWHERE:
        return "Received";
      case MISSED:
        return "Missed";
      case REJECTED:
        return "Declined";
      default:
        return "Call";
    }
  }

  /**
   * When a call was, as short as it can be said: the time for a call today ("2:14p", or "14:14"
   * on a 24-hour clock), the day and the time for the six days before ("Wed 2:14p"), and the date
   * for anything older ("Nov 3").
   */
  static String when(long atMs, long nowMs, TimeZone zone, boolean twentyFourHours) {
    Calendar at = Calendar.getInstance(zone);
    at.setTimeInMillis(atMs);
    Calendar now = Calendar.getInstance(zone);
    now.setTimeInMillis(nowMs);
    long days = dayNumber(now) - dayNumber(at);
    if (days < 0 || days > 6) {
      return MONTHS[at.get(Calendar.MONTH)] + " " + at.get(Calendar.DAY_OF_MONTH);
    }
    String time = time(at, twentyFourHours);
    return days == 0 ? time : DAYS[at.get(Calendar.DAY_OF_WEEK) - 1] + " " + time;
  }

  /** The line under the name: at most 19 characters, which is what fits on the watch. */
  static String second(int type, long atMs, long nowMs, TimeZone zone, boolean twentyFourHours) {
    return kind(type) + " " + when(atMs, nowMs, zone, twentyFourHours);
  }

  private static String time(Calendar at, boolean twentyFourHours) {
    int hour = at.get(Calendar.HOUR_OF_DAY);
    int minute = at.get(Calendar.MINUTE);
    String minutes = (minute < 10 ? "0" : "") + minute;
    if (twentyFourHours) {
      return hour + ":" + minutes;
    }
    int shown = hour % 12 == 0 ? 12 : hour % 12;
    return shown + ":" + minutes + (hour < 12 ? "a" : "p");
  }

  /** Counts days so that two moments of one calendar day get one number, in the calendar's own time zone. */
  private static long dayNumber(Calendar c) {
    long local = c.getTimeInMillis() + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET);
    return Math.floorDiv(local, 86_400_000L);
  }
}
