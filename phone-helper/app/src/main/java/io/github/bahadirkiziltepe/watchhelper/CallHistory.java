package io.github.bahadirkiziltepe.watchhelper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * The phone's call history, for the list on the watch that lies one swipe past the contacts, and
 * for suggesting people who called to be put on the watch.
 *
 * <p>It is read from Android's call log, which holds ordinary phone calls: made, received, missed
 * and declined ones. Of each call the watch gets a name (or the number, when the phone has no
 * name for it), what kind of call it was and when. The number itself stays on the phone and is
 * only used when the watch asks to call that person back. Nothing of this is ever logged.
 */
final class CallHistory {
  /** As many calls as the watch shows: ten pages of four. */
  static final int MAX = 40;

  /** One call. */
  static final class Entry {
    /** The name the phone has for the caller, or empty. */
    final String name;
    /** The number, or empty if the caller kept it private. */
    final String number;
    /** One of the CallRows kinds. */
    final int type;
    final long at;

    Entry(String name, String number, int type, long at) {
      this.name = name;
      this.number = number;
      this.type = type;
      this.at = at;
    }
  }

  private final Context app;
  private final CallWatcher calls;

  /** The calls the watch was last shown, and a number that changes whenever they do. */
  private List<Entry> shown = Collections.emptyList();
  private String signature = "";
  private int generation = 1;

  CallHistory(Context app, CallWatcher calls) {
    this.app = app;
    this.calls = calls;
  }

  boolean allowed() {
    return app.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Reads the call log again, for the watch's list. Returns the calls, newest first, or null if
   * Android does not let the app read them. Main thread.
   */
  List<Entry> refresh() {
    List<Entry> read = read(MAX, 0, false);
    if (read == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (Entry entry : read) {
      sb.append(entry.number).append('|').append(entry.type).append('|').append(entry.at).append('\n');
    }
    if (!sb.toString().equals(signature)) {
      signature = sb.toString();
      generation = generation % 250 + 1;
    }
    shown = read;
    return shown;
  }

  /** The calls as the watch was last shown them. */
  List<Entry> shown() {
    return shown;
  }

  /** Names the list the watch was shown, so that a row it asks about later is known to be the same call. */
  int generation() {
    return generation;
  }

  /**
   * The calls that came in after {@code since}, newest first, each with the name the phone's
   * contacts have for the caller, or no name if they have none. Null if the call log may not be
   * read. Any thread.
   */
  List<Entry> incomingSince(long since) {
    return read(MAX * 2, since, true);
  }

  /** What the watch shows on the first line of a row: who it was. */
  String label(Entry entry) {
    if (!entry.name.isEmpty()) {
      return entry.name;
    }
    if (entry.number.isEmpty()) {
      return "Private number";
    }
    String known = calls.contactName(entry.number);
    return known.isEmpty() ? entry.number : known;
  }

  /** And on the second: made, received or missed, and when. */
  String second(Entry entry) {
    return CallRows.second(entry.type, entry.at, System.currentTimeMillis(), TimeZone.getDefault(),
        DateFormat.is24HourFormat(app));
  }

  private List<Entry> read(int limit, long since, boolean incomingOnly) {
    if (!allowed()) {
      return null;
    }
    List<Entry> out = new ArrayList<>();
    // Twice as many rows as wanted are asked for, because voicemail and blocked calls are left out
    Uri uri = CallLog.Calls.CONTENT_URI.buildUpon()
        .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, String.valueOf(limit * 2)).build();
    String[] columns = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE};
    try (Cursor cursor = app.getContentResolver().query(uri, columns, null, null, CallLog.Calls.DEFAULT_SORT_ORDER)) {
      while (cursor != null && cursor.moveToNext() && out.size() < limit) {
        int type = cursor.getInt(2);
        long at = cursor.getLong(3);
        if (at <= since) {
          // Newest first: everything from here on is older still
          break;
        }
        if (!CallRows.shown(type) || (incomingOnly && !CallRows.incoming(type))) {
          continue;
        }
        String number = usable(cursor.getString(0));
        String name = clean(cursor.getString(1));
        if (incomingOnly) {
          // For suggesting somebody only a name from the phone's own contacts counts. The call log
          // may also hold a name that a caller-ID service made up.
          name = number.isEmpty() ? "" : calls.contactName(number);
        }
        out.add(new Entry(name, number, type, at));
      }
    } catch (RuntimeException e) {
      return null;
    }
    return out;
  }

  private static String clean(String text) {
    return text == null ? "" : text.trim();
  }

  /** A number that can be called, or nothing. Android writes "-1", "-2" and so on for callers without one. */
  private static String usable(String number) {
    String trimmed = clean(number);
    return trimmed.startsWith("-") ? "" : trimmed;
  }
}
