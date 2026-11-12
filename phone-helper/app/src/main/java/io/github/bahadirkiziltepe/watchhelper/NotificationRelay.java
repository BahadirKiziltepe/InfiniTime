package io.github.bahadirkiziltepe.watchhelper;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the watch's notification screen the same as the phone's notification shade, for the apps
 * listed below: a notification goes to the watch when it appears on the phone and is taken back
 * when it disappears from the phone, whether it was read, swiped away or cleared.
 *
 * <p>What a notification says goes to the watch and nowhere else. The log and the report only
 * note that something was sent, from which app, and how long it was: never who wrote or what.
 */
final class NotificationRelay {
  /** The apps whose notifications go to the watch, and what the watch calls them. */
  private static final Map<String, String> LABELS = new HashMap<>();

  static {
    LABELS.put("com.google.android.apps.messaging", "Text");
    LABELS.put("com.samsung.android.messaging", "Text");
    LABELS.put("com.whatsapp", "WhatsApp");
    LABELS.put("com.whatsapp.w4b", "WhatsApp");
    LABELS.put("com.discord", "Discord");
  }

  /** How many notifications the watch holds. Older ones wait on the phone until there is room. */
  private static final int WATCH_KEEPS = 8;
  /** Apps often take a notification away and put the same one straight back. That is not news. */
  private static final long REPOST_QUIET_MS = 3_000;
  private static final long REFILL_DELAY_MS = 1_500;
  private static final long TEST_LASTS_MS = 60_000;
  private static final int TEST_ID = 0xffff;
  private static final int MAX_REMEMBERED = 60;

  private static final class Entry {
    int id;
    String label;
    String title;
    String body;
    int hour;
    int minute;
    /** Everything that makes this notification what it is. Two with the same signature are the same. */
    String signature;
  }

  private static final class Gone {
    final String signature;
    final long at;

    Gone(String signature, long at) {
      this.signature = signature;
      this.at = at;
    }
  }

  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;

  /** What the phone is showing from these apps, oldest first, by Android's key for each. */
  private final LinkedHashMap<String, Entry> shown = new LinkedHashMap<>();
  private final Map<String, Gone> recentlyGone = new HashMap<>();
  private int nextId = 1;
  private boolean testShowing;

  // Counters for the screen and the report.
  private int sentCount;
  private int alertCount;
  private int takenBackCount;
  private int skippedCount;
  private String lastSkipped = "";

  private final Runnable refill = this::pushAll;
  private final Runnable endTest = this::removeTest;

  NotificationRelay(Handler main, EventLog log, WatchLink watch) {
    this.main = main;
    this.log = log;
    this.watch = watch;
  }

  static boolean relays(String packageName) {
    return LABELS.containsKey(packageName);
  }

  // ---------------------------------------------------------------- from the phone

  void onPosted(StatusBarNotification sbn, NotificationListenerService.RankingMap ranking) {
    String label = LABELS.get(sbn.getPackageName());
    if (label == null) {
      return;
    }
    String key = sbn.getKey();
    Entry fresh = read(sbn, label);
    if (fresh == null) {
      return;
    }
    Entry old = shown.get(key);
    if (old != null && old.signature.equals(fresh.signature)) {
      return;
    }

    boolean alert = wouldAlert(sbn, ranking);
    Notification n = sbn.getNotification();
    if (old != null && n != null && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
      // The phone stays quiet for this update, so the watch does too.
      alert = false;
    }
    Gone gone = recentlyGone.remove(key);
    if (gone != null && gone.signature.equals(fresh.signature)
        && SystemClock.elapsedRealtime() - gone.at < REPOST_QUIET_MS) {
      alert = false;
    }

    fresh.id = old != null ? old.id : newId();
    shown.remove(key);
    shown.put(key, fresh);
    trim();
    send(fresh, alert);
    log.add("notify", label + " notification sent to the watch" + (alert ? "" : " (quietly)") + ": name "
        + fresh.title.length() + ", text " + fresh.body.length() + " characters");
  }

  void onRemoved(StatusBarNotification sbn) {
    String key = sbn.getKey();
    Entry old = shown.remove(key);
    if (old == null) {
      return;
    }
    recentlyGone.put(key, new Gone(old.signature, SystemClock.elapsedRealtime()));
    if (recentlyGone.size() > MAX_REMEMBERED) {
      recentlyGone.clear();
    }
    takenBackCount++;
    watch.sendPhone(PhoneFrames.remove(old.id));
    log.add("notify", old.label + " notification gone from the phone, taken off the watch");
    if (shown.size() >= WATCH_KEEPS) {
      // There are older ones the watch had no room for. Now it has.
      main.removeCallbacks(refill);
      main.postDelayed(refill, REFILL_DELAY_MS);
    }
  }

  /**
   * Starts again from what the phone is showing right now. Used when notification access comes up
   * and whenever the watch links, since either side may have missed something in between.
   */
  void sync(StatusBarNotification[] active, NotificationListenerService.RankingMap ranking) {
    if (active != null) {
      List<Entry> found = new ArrayList<>();
      List<String> keys = new ArrayList<>();
      List<Long> times = new ArrayList<>();
      for (StatusBarNotification sbn : active) {
        String label = sbn == null ? null : LABELS.get(sbn.getPackageName());
        if (label == null) {
          continue;
        }
        Entry entry = read(sbn, label);
        if (entry == null) {
          continue;
        }
        Entry old = shown.get(sbn.getKey());
        entry.id = old != null ? old.id : newId();
        // Oldest first, going by when Android last saw each one change.
        int at = 0;
        while (at < times.size() && times.get(at) <= sbn.getPostTime()) {
          at++;
        }
        found.add(at, entry);
        keys.add(at, sbn.getKey());
        times.add(at, sbn.getPostTime());
      }
      shown.clear();
      for (int i = 0; i < found.size(); i++) {
        shown.put(keys.get(i), found.get(i));
      }
      trim();
    }
    pushAll();
  }

  void onWatchReady() {
    testShowing = false;
    main.removeCallbacks(endTest);
    StatusBarNotification[] active = MediaListenerService.showing();
    if (active != null) {
      sync(active, MediaListenerService.ranking());
    } else {
      pushAll();
    }
  }

  /** The watch may have lost part of what was sent. Send everything again. */
  void resend() {
    main.removeCallbacks(refill);
    main.postDelayed(refill, REFILL_DELAY_MS);
  }

  // ---------------------------------------------------------------- to the watch

  private void send(Entry entry, boolean alert) {
    if (watch.sendPhone(PhoneFrames.add(entry.id, entry.hour, entry.minute, alert, entry.label, entry.title,
        entry.body))) {
      sentCount++;
      if (alert) {
        alertCount++;
      }
    }
  }

  /** Empties the watch's list and fills it again with the newest ones, without buzzing. */
  private void pushAll() {
    main.removeCallbacks(refill);
    if (!watch.hasPhoneChannel()) {
      return;
    }
    watch.sendPhone(PhoneFrames.clear());
    int skip = Math.max(0, shown.size() - WATCH_KEEPS);
    for (Entry entry : shown.values()) {
      if (skip > 0) {
        skip--;
        continue;
      }
      send(entry, false);
    }
    testShowing = false;
  }

  /** Shows a made-up notification on the watch for a minute, to see that this works. */
  void sendTest() {
    if (!watch.hasPhoneChannel()) {
      log.add("notify", "test: the watch is not linked, or its build is too old for notifications");
      return;
    }
    ZonedDateTime now = ZonedDateTime.now();
    watch.sendPhone(PhoneFrames.add(TEST_ID, now.getHour(), now.getMinute(), true, "Text", "Watch Helper",
        "This is a test. Tap to go on to the next notification, double tap to go back. It goes away by itself "
            + "in a minute."));
    testShowing = true;
    log.add("notify", "test notification sent to the watch");
    main.removeCallbacks(endTest);
    main.postDelayed(endTest, TEST_LASTS_MS);
  }

  private void removeTest() {
    if (testShowing) {
      testShowing = false;
      watch.sendPhone(PhoneFrames.remove(TEST_ID));
      log.add("notify", "test notification taken off the watch");
    }
  }

  // ---------------------------------------------------------------- reading a notification

  /** What the watch should show for this notification, or null if it is not one to show. */
  private Entry read(StatusBarNotification sbn, String label) {
    Notification n = sbn.getNotification();
    if (n == null || n.extras == null) {
      return skip("empty");
    }
    if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
      // The line that stands for a whole bundle. The notifications inside it arrive on their own.
      return skip("a summary of several");
    }
    if (sbn.isOngoing()) {
      return skip("a status line, not a message");
    }
    if (Notification.CATEGORY_CALL.equals(n.category)) {
      return skip("a call");
    }
    Bundle extras = n.extras;
    String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
    String body = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
    if (body.isEmpty()) {
      body = text(extras.getCharSequence(Notification.EXTRA_TEXT));
    }
    long when = n.when > 0 ? n.when : sbn.getPostTime();

    // A chat notification lists its messages. The newest one is the news, with who wrote it.
    try {
      Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
      for (int i = messages == null ? -1 : messages.length - 1; i >= 0; i--) {
        if (!(messages[i] instanceof Bundle)) {
          continue;
        }
        Bundle message = (Bundle) messages[i];
        String words = text(message.getCharSequence("text"));
        if (words.isEmpty()) {
          continue;
        }
        body = words;
        long time = message.getLong("time", 0);
        if (time > 0) {
          when = time;
        }
        String sender = text(message.getCharSequence("sender"));
        Person person = message.getParcelable("sender_person", Person.class);
        if (person != null && person.getName() != null) {
          sender = text(person.getName());
        }
        String conversation = text(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        boolean group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        if (group && !conversation.isEmpty()) {
          title = sender.isEmpty() ? conversation : sender + " @ " + conversation;
        } else if (!sender.isEmpty()) {
          title = sender;
        }
        break;
      }
    } catch (RuntimeException e) {
      // Not laid out as expected. The plain title and text read above still stand.
    }

    if (body.isEmpty()) {
      return skip("no text");
    }
    Entry entry = new Entry();
    entry.label = label;
    entry.title = title;
    entry.body = body;
    ZonedDateTime at = Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault());
    entry.hour = at.getHour();
    entry.minute = at.getMinute();
    entry.signature = title + '\n' + body + '\n' + when;
    return entry;
  }

  private Entry skip(String why) {
    skippedCount++;
    lastSkipped = why;
    return null;
  }

  private static String text(CharSequence in) {
    return in == null ? "" : in.toString().trim();
  }

  /** Whether the phone itself makes a sound or buzzes for this notification. */
  private static boolean wouldAlert(StatusBarNotification sbn, NotificationListenerService.RankingMap map) {
    try {
      NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
      if (map == null || !map.getRanking(sbn.getKey(), ranking)) {
        return true;
      }
      if (ranking.isSuspended() || !ranking.matchesInterruptionFilter()) {
        // Paused app, or Do Not Disturb is keeping it quiet
        return false;
      }
      return ranking.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
    } catch (RuntimeException e) {
      return true;
    }
  }

  private int newId() {
    for (int tries = 0; tries < 0x10000; tries++) {
      int id = nextId;
      nextId = nextId >= TEST_ID - 1 ? 1 : nextId + 1;
      boolean taken = false;
      for (Entry entry : shown.values()) {
        taken |= entry.id == id;
      }
      if (!taken) {
        return id;
      }
    }
    return 1;
  }

  /** The phone can pile up far more than the watch shows. Forget the oldest beyond a sane number. */
  private void trim() {
    while (shown.size() > MAX_REMEMBERED) {
      String oldest = shown.keySet().iterator().next();
      shown.remove(oldest);
    }
  }

  // ---------------------------------------------------------------- status text

  /** A line or two for the screen. */
  String describe() {
    if (!watch.isReady()) {
      return "Notifications: waiting for the watch.";
    }
    if (!watch.hasPhoneChannel()) {
      return "✗ Notifications: this watch build cannot show them yet. Install watch build 6 or newer.";
    }
    StringBuilder sb = new StringBuilder("✓ Notifications: ");
    if (shown.isEmpty()) {
      sb.append("none on the phone right now");
    } else {
      sb.append(shown.size()).append(" on the phone (").append(countByLabel()).append(")");
      if (shown.size() > WATCH_KEEPS) {
        sb.append(", the newest ").append(WATCH_KEEPS).append(" on the watch");
      }
    }
    sb.append(". Text, WhatsApp and Discord are passed on.");
    return sb.toString();
  }

  private String countByLabel() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Entry entry : shown.values()) {
      counts.merge(entry.label, 1, Integer::sum);
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Integer> count : counts.entrySet()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(count.getKey()).append(' ').append(count.getValue());
    }
    return sb.toString();
  }

  /** For the report. Says how much there is, never what it says. */
  String dump() {
    StringBuilder sb = new StringBuilder();
    sb.append("Watch can show notifications: ").append(watch.hasPhoneChannel() ? "yes" : "no").append('\n');
    sb.append("On the phone now: ").append(shown.size());
    if (!shown.isEmpty()) {
      sb.append(" (").append(countByLabel()).append(")");
    }
    sb.append('\n');
    sb.append("Sent to the watch: ").append(sentCount).append(", of those with a buzz: ").append(alertCount)
        .append(", taken back: ").append(takenBackCount).append('\n');
    sb.append("Not passed on: ").append(skippedCount).append(lastSkipped.isEmpty() ? "" : ", last because: " + lastSkipped)
        .append('\n');
    return sb.toString();
  }
}
