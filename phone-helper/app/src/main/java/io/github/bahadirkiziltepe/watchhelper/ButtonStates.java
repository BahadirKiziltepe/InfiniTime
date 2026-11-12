package io.github.bahadirkiziltepe.watchhelper;

import java.util.Locale;

/**
 * Reads the state of Spotify's shuffle, repeat and save buttons.
 *
 * <p>Spotify does not report smart shuffle, or whether a song is saved, through the standard
 * media controls. It does offer each button to other apps, and the way it describes them gives
 * the state away: the button's internal id names what a press will do next, and its picture
 * shows what is on now. The ids and picture names below were read from Spotify 9.1 on the
 * user's phone (see the app's report).
 *
 * <p>The numbers returned are the ones the watch firmware expects (MusicService.h).
 * No Android imports on purpose, so this can be checked on a desktop JVM.
 */
final class ButtonStates {
  static final int UNKNOWN = 0xff;
  static final int SHUFFLE_OFF = 0;
  static final int SHUFFLE_ON = 1;
  static final int SHUFFLE_SMART = 2;
  static final int REPEAT_OFF = 0;
  static final int REPEAT_ALL = 1;
  static final int REPEAT_ONE = 2;
  static final int SAVED_NO = 0;
  static final int SAVED_YES = 1;

  private ButtonStates() {
  }

  static int shuffle(String id, String picture) {
    switch (upper(id)) {
      case "TURN_SHUFFLE_ON":
        return SHUFFLE_OFF;
      case "TURN_SHUFFLE_OFF":
        return SHUFFLE_ON;
      case "TURN_SMART_SHUFFLE_OFF":
        return SHUFFLE_SMART;
      case "TURN_SMART_SHUFFLE_ON":
        // Not seen so far. It would be offered while plain shuffle is on.
        return SHUFFLE_ON;
      default:
        break;
    }
    String name = lower(picture);
    if (name.contains("smart")) {
      return SHUFFLE_SMART;
    }
    if (name.contains("shuffle")) {
      return name.contains("active") ? SHUFFLE_ON : SHUFFLE_OFF;
    }
    return UNKNOWN;
  }

  static int repeat(String id, String picture) {
    switch (upper(id)) {
      case "TURN_REPEAT_ALL_ON":
        return REPEAT_OFF;
      case "TURN_REPEAT_ONE_ON":
        return REPEAT_ALL;
      case "TURN_REPEAT_OFF":
        return REPEAT_ONE;
      default:
        break;
    }
    String name = lower(picture);
    if (name.contains("repeat_one")) {
      return REPEAT_ONE;
    }
    if (name.contains("repeat_all")) {
      return REPEAT_ALL;
    }
    if (name.contains("repeat_off")) {
      return REPEAT_OFF;
    }
    return UNKNOWN;
  }

  static int saved(String id, String label, String picture) {
    switch (upper(id)) {
      case "ADD_TO":
        return SAVED_NO;
      case "CHECK_FILL":
        return SAVED_YES;
      default:
        break;
    }
    String name = lower(picture);
    if (name.contains("check")) {
      return SAVED_YES;
    }
    if (name.contains("plus")) {
      return SAVED_NO;
    }
    String text = upper(label);
    if (text.startsWith("REMOVE")) {
      return SAVED_YES;
    }
    if (text.startsWith("ADD")) {
      return SAVED_NO;
    }
    return UNKNOWN;
  }

  static String shuffleName(int state) {
    switch (state) {
      case SHUFFLE_OFF:
        return "off";
      case SHUFFLE_ON:
        return "on";
      case SHUFFLE_SMART:
        return "smart";
      default:
        return "unknown";
    }
  }

  static String repeatName(int state) {
    switch (state) {
      case REPEAT_OFF:
        return "off";
      case REPEAT_ALL:
        return "all";
      case REPEAT_ONE:
        return "one song";
      default:
        return "unknown";
    }
  }

  static String savedName(int state) {
    switch (state) {
      case SAVED_NO:
        return "no";
      case SAVED_YES:
        return "yes";
      default:
        return "unknown";
    }
  }

  private static String upper(String s) {
    return s == null ? "" : s.toUpperCase(Locale.ROOT);
  }

  private static String lower(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT);
  }
}
