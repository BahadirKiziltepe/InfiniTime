package io.github.bahadirkiziltepe.watchhelper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the messages that carry notifications and calls to the watch. The watch's side of this is
 * in src/components/ble/MusicService.cpp in this repo.
 *
 * <p>Every message fits one Bluetooth packet of 20 bytes, so a long notification is sent in pieces
 * and nothing depends on the watch putting an over-long message back together: it only has room to
 * do that for short ones.
 *
 * <p>No Android imports on purpose, so this can be checked on a desktop JVM.
 */
final class PhoneFrames {
  /** The most one message can hold. */
  static final int MAX_MESSAGE = 20;
  /** What the watch keeps: the app's name, who it is from, and title plus text together. */
  static final int MAX_LABEL = 12;
  static final int MAX_TITLE = 40;
  static final int MAX_TEXT = 239;
  static final int MAX_CALLER = 40;

  /** Between two messages of one conversation. The watch shows it as an empty line. */
  private static final byte[] BETWEEN = {'\n', '\n'};
  /** Stands in for older messages that there was no room for. */
  private static final byte[] EARLIER = "...".getBytes(StandardCharsets.UTF_8);

  static final int CALL_NONE = 0;
  static final int CALL_RINGING = 1;
  static final int CALL_ACTIVE = 2;

  private static final int BEGIN = 0x01;
  private static final int REMOVE = 0x02;
  private static final int CLEAR = 0x03;
  private static final int TEXT = 0x04;
  private static final int END = 0x05;
  private static final int CALL = 0x10;
  private static final int CALL_MORE = 0x11;

  // Lists the watch asks for, one page at a time (see src/components/ble/PhoneLists.h).
  static final int LIST_QUEUE = 1;
  static final int LIST_PLAYLISTS = 2;
  static final int LIST_SONGS = 3;
  static final int LIST_CONTACTS = 4;
  /** How many rows the watch shows at once, and so how many a page holds. */
  static final int ROWS_PER_PAGE = 4;
  static final int MAX_ROW = 33;
  static final int MAX_LIST_TITLE = 17;
  static final int MAX_NOTE = 72;
  static final int MAX_RESULT = 17;
  /** A song Spotify picked itself: it is not in the playlist that is playing. */
  static final int ROW_SMART = 0x01;
  /** The song that is playing. */
  static final int ROW_PLAYING = 0x02;
  /** A playlist whose songs cannot be listed. */
  static final int ROW_CLOSED = 0x04;
  static final int RESULT_DONE = 0;
  static final int RESULT_FAILED = 1;
  /** The list is no longer the one the watch's page came from. The watch asks for the page again. */
  static final int RESULT_STALE = 2;

  private static final int LIST_BEGIN = 0x20;
  private static final int LIST_ROW = 0x21;
  private static final int LIST_ROW_MORE = 0x22;
  private static final int LIST_TITLE = 0x23;
  private static final int LIST_NOTE = 0x24;
  private static final int LIST_END = 0x25;
  private static final int LIST_RESULT = 0x28;

  /** One row of a list page. */
  static final class Row {
    final String text;
    final int flags;

    Row(String text, int flags) {
      this.text = text;
      this.flags = flags;
    }
  }

  private PhoneFrames() {
  }

  /**
   * A notification, new or changed. {@code id} is this app's number for it (1 to 65535), used
   * again to take it back. {@code alert} makes the watch buzz and show it. {@code messages} are
   * the conversation's unread messages, oldest first; when they do not all fit, the newest win.
   */
  static List<byte[]> add(int id, int hour, int minute, boolean alert, String label, String title,
      List<String> messages) {
    byte[] labelBytes = WatchText.encode(label, MAX_LABEL);
    byte[] titleBytes = WatchText.encode(title, MAX_TITLE);
    byte[] bodyBytes = body(messages, MAX_TEXT - 1 - titleBytes.length);

    List<byte[]> out = new ArrayList<>();
    byte[] begin = new byte[7 + labelBytes.length];
    begin[0] = (byte) BEGIN;
    begin[1] = (byte) id;
    begin[2] = (byte) (id >> 8);
    begin[3] = (byte) clamp(hour, 0, 23);
    begin[4] = (byte) clamp(minute, 0, 59);
    begin[5] = (byte) (alert ? 1 : 0);
    begin[6] = (byte) labelBytes.length;
    System.arraycopy(labelBytes, 0, begin, 7, labelBytes.length);
    out.add(begin);

    // Title, a zero byte, then the messages: the watch splits them at the zero.
    byte[] text = new byte[titleBytes.length + 1 + bodyBytes.length];
    System.arraycopy(titleBytes, 0, text, 0, titleBytes.length);
    System.arraycopy(bodyBytes, 0, text, titleBytes.length + 1, bodyBytes.length);
    addPieces(out, TEXT, text, 0);

    out.add(new byte[] {(byte) END});
    return out;
  }

  /**
   * The messages one under the other with an empty line between them, in at most {@code budget}
   * bytes. Starting from the newest, as many whole messages as fit are kept; "..." on top says
   * that there are older ones on the phone. A single message that is too long is cut short.
   */
  static byte[] body(List<String> messages, int budget) {
    List<byte[]> kept = new ArrayList<>();
    int used = 0;
    boolean dropped = false;
    for (int i = messages.size() - 1; i >= 0; i--) {
      byte[] message = WatchText.fold(messages.get(i)).getBytes(StandardCharsets.UTF_8);
      if (message.length == 0) {
        continue;
      }
      int needed = message.length + (kept.isEmpty() ? 0 : BETWEEN.length);
      if (used + needed > budget) {
        if (kept.isEmpty()) {
          // Even the newest one alone is too long
          message = WatchText.encode(messages.get(i), budget);
          kept.add(message);
          used = message.length;
          dropped = i > 0;
        } else {
          dropped = true;
        }
        break;
      }
      kept.add(0, message);
      used += needed;
    }
    if (dropped && used + EARLIER.length + BETWEEN.length <= budget) {
      kept.add(0, EARLIER);
      used += EARLIER.length + BETWEEN.length;
    }

    byte[] out = new byte[used];
    int at = 0;
    for (int i = 0; i < kept.size(); i++) {
      if (i > 0) {
        System.arraycopy(BETWEEN, 0, out, at, BETWEEN.length);
        at += BETWEEN.length;
      }
      System.arraycopy(kept.get(i), 0, out, at, kept.get(i).length);
      at += kept.get(i).length;
    }
    return out;
  }

  static List<byte[]> remove(int id) {
    List<byte[]> out = new ArrayList<>();
    out.add(new byte[] {(byte) REMOVE, (byte) id, (byte) (id >> 8)});
    return out;
  }

  static List<byte[]> clear() {
    List<byte[]> out = new ArrayList<>();
    out.add(new byte[] {(byte) CLEAR});
    return out;
  }

  /** The state of the phone's call (one of the CALL_ values) and who it is with. */
  static List<byte[]> call(int state, String caller) {
    byte[] name = WatchText.encode(caller, MAX_CALLER);
    int first = Math.min(name.length, MAX_MESSAGE - 2);

    List<byte[]> out = new ArrayList<>();
    byte[] head = new byte[2 + first];
    head[0] = (byte) CALL;
    head[1] = (byte) state;
    System.arraycopy(name, 0, head, 2, first);
    out.add(head);
    addPieces(out, CALL_MORE, name, first);
    return out;
  }

  /**
   * A page of a list, in answer to the watch's request number {@code token}. {@code offset} is the
   * place of the first row in the whole list and {@code total} the length of that list.
   * {@code snapshot} names the list the page was cut from; the watch sends it back when it asks for
   * something to be done with a row. {@code title} may be empty. {@code note} is a sentence the
   * watch shows when there are no rows.
   */
  static List<byte[]> listPage(int kind, int token, int offset, int total, int snapshot, String title, String note,
      List<Row> rows) {
    int count = Math.min(rows == null ? 0 : rows.size(), ROWS_PER_PAGE);
    List<byte[]> out = new ArrayList<>();
    out.add(new byte[] {(byte) LIST_BEGIN, (byte) kind, (byte) token, (byte) offset, (byte) (offset >> 8),
        (byte) total, (byte) (total >> 8), (byte) count, (byte) snapshot});

    byte[] titleBytes = WatchText.encode(title == null ? "" : title, MAX_LIST_TITLE);
    if (titleBytes.length > 0) {
      out.add(piece(new byte[] {(byte) LIST_TITLE, (byte) token}, titleBytes, 0, titleBytes.length));
    }
    for (int slot = 0; slot < count; slot++) {
      Row row = rows.get(slot);
      byte[] text = WatchText.encode(row.text == null ? "" : row.text, MAX_ROW);
      int first = Math.min(text.length, MAX_MESSAGE - 4);
      out.add(piece(new byte[] {(byte) LIST_ROW, (byte) token, (byte) slot, (byte) row.flags}, text, 0, first));
      for (int at = first; at < text.length;) {
        int size = Math.min(MAX_MESSAGE - 3, text.length - at);
        out.add(piece(new byte[] {(byte) LIST_ROW_MORE, (byte) token, (byte) slot}, text, at, size));
        at += size;
      }
    }
    byte[] noteBytes = WatchText.encode(note == null ? "" : note, MAX_NOTE);
    for (int at = 0; at < noteBytes.length;) {
      int size = Math.min(MAX_MESSAGE - 2, noteBytes.length - at);
      out.add(piece(new byte[] {(byte) LIST_NOTE, (byte) token}, noteBytes, at, size));
      at += size;
    }
    out.add(new byte[] {(byte) LIST_END, (byte) token});
    return out;
  }

  /** What became of something the watch asked for: one of the RESULT_ values and a few words. */
  static List<byte[]> listResult(int kind, int result, String text) {
    byte[] words = WatchText.encode(text == null ? "" : text, MAX_RESULT);
    List<byte[]> out = new ArrayList<>();
    out.add(piece(new byte[] {(byte) LIST_RESULT, (byte) kind, (byte) result}, words, 0, words.length));
    return out;
  }

  /** True for the messages that make up a list page. They may be sent without waiting for an answer to each. */
  static boolean isListMessage(byte[] message) {
    return message != null && message.length > 0 && (message[0] & 0xf0) == 0x20;
  }

  private static byte[] piece(byte[] head, byte[] data, int from, int size) {
    byte[] out = new byte[head.length + size];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(data, from, out, head.length, size);
    return out;
  }

  /** Splits {@code data}, from {@code from} on, into messages that each start with {@code code}. */
  private static void addPieces(List<byte[]> out, int code, byte[] data, int from) {
    for (int at = from; at < data.length;) {
      int size = Math.min(MAX_MESSAGE - 1, data.length - at);
      byte[] piece = new byte[1 + size];
      piece[0] = (byte) code;
      System.arraycopy(data, at, piece, 1, size);
      out.add(piece);
      at += size;
    }
  }

  private static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(high, value));
  }
}
