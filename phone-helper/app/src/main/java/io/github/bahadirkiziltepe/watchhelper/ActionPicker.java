package io.github.bahadirkiziltepe.watchhelper;

import java.util.Locale;

/**
 * Guesses what one of Spotify's own player buttons does from its internal id and its label.
 *
 * <p>Spotify does not publish these ids, so this is a best guess that the report lets us check.
 * No Android imports on purpose, so this can be checked on a desktop JVM.
 */
final class ActionPicker {
  static final int OTHER = 0;
  static final int SHUFFLE = 1;
  static final int REPEAT = 2;
  static final int ADD = 3;

  private ActionPicker() {
  }

  /** The id decides when it says anything at all; the (possibly translated) label is the fallback. */
  static int kind(String id, String label) {
    String key = upper(id);
    if (rejected(key)) {
      return OTHER;
    }
    int byId = match(key);
    if (byId != OTHER) {
      return byId;
    }
    String text = upper(label);
    if (rejected(text)) {
      return OTHER;
    }
    return match(text);
  }

  static String name(int kind) {
    switch (kind) {
      case SHUFFLE:
        return "shuffle";
      case REPEAT:
        return "repeat";
      case ADD:
        return "add";
      default:
        return "other";
    }
  }

  private static String upper(String s) {
    return s == null ? "" : s.toUpperCase(Locale.ROOT);
  }

  /** Buttons that share a word with "add" or "like" but mean the opposite of keeping the song. */
  private static boolean rejected(String s) {
    if (s.contains("DISLIKE")) {
      return true;
    }
    for (String word : words(s)) {
      if (word.equals("BAN") || word.startsWith("HIDE") || word.startsWith("BLOCK")) {
        return true;
      }
    }
    return false;
  }

  private static int match(String s) {
    if (s.contains("SHUFFLE")) {
      return SHUFFLE;
    }
    if (s.contains("REPEAT")) {
      return REPEAT;
    }
    if (s.contains("COLLECTION") || s.contains("HEART") || s.contains("FAVORITE") || s.contains("FAVOURITE")
        || s.contains("LIBRARY") || s.contains("PLAYLIST") || s.contains("UNLIKE")) {
      return ADD;
    }
    for (String word : words(s)) {
      if (word.equals("ADD") || word.startsWith("LIKE") || word.startsWith("SAVE")) {
        return ADD;
      }
    }
    return OTHER;
  }

  private static String[] words(String s) {
    return s.split("[^A-Z0-9]+");
  }
}
