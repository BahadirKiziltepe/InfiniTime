package io.github.bahadirkiziltepe.watchhelper;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Short in-memory history of what happened, shown on screen and included in the report. */
final class EventLog {
  private static final int MAX_LINES = 400;
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

  private final ArrayDeque<String> lines = new ArrayDeque<>();
  private String lastBody = "";
  private int repeats;
  private long dropped;

  synchronized void add(String tag, String message) {
    String body = tag + ": " + message;
    if (body.equals(lastBody) && !lines.isEmpty()) {
      // The same thing again: keep one line and count it, so a chatty source cannot flood the log.
      repeats++;
      lines.removeLast();
      lines.addLast(now() + " " + body + " (x" + (repeats + 1) + ")");
      return;
    }
    lastBody = body;
    repeats = 0;
    if (lines.size() >= MAX_LINES) {
      lines.removeFirst();
      dropped++;
    }
    lines.addLast(now() + " " + body);
  }

  synchronized List<String> all() {
    return new ArrayList<>(lines);
  }

  synchronized List<String> tail(int count) {
    List<String> all = new ArrayList<>(lines);
    int from = Math.max(0, all.size() - count);
    return new ArrayList<>(all.subList(from, all.size()));
  }

  synchronized long dropped() {
    return dropped;
  }

  private static String now() {
    return LocalTime.now().format(CLOCK);
  }
}
