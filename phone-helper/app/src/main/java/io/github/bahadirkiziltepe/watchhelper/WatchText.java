package io.github.bahadirkiziltepe.watchhelper;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Text and number encoding for the watch's music service.
 *
 * <p>No Android imports on purpose, so this can be checked on a desktop JVM.
 */
final class WatchText {
  /** The watch keeps at most this many bytes of a title, artist or album. */
  static final int MAX_BYTES = 40;

  private static final String ELLIPSIS = "...";

  private WatchText() {
  }

  /**
   * Replaces characters the watch cannot draw with the closest ones it can.
   *
   * <p>The watch's text font only has plain ASCII, the basic Cyrillic block and the degree sign.
   * Anything else would show up as a gap, so accents are stripped (s-cedilla becomes s, dotless i
   * becomes i) and characters with no close match collapse into a single question mark.
   */
  static String fold(String in) {
    if (in == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(in.length());
    boolean lastUnknown = false;
    for (int i = 0; i < in.length();) {
      int cp = in.codePointAt(i);
      i += Character.charCount(cp);

      int before = out.length();
      boolean known = appendFolded(out, cp);
      if (known) {
        if (out.length() > before) {
          lastUnknown = false;
        }
      } else if (!lastUnknown) {
        out.append('?');
        lastUnknown = true;
      }
    }
    return collapseSpaces(out);
  }

  /**
   * UTF-8 bytes of the folded text, at most {@code limit} bytes, cut between characters and ending
   * in "..." when something had to be left out.
   */
  static byte[] encode(String in, int limit) {
    String text = fold(in);
    byte[] all = text.getBytes(StandardCharsets.UTF_8);
    if (limit <= 0) {
      return new byte[0];
    }
    if (all.length <= limit) {
      return all;
    }
    int budget = limit > ELLIPSIS.length() ? limit - ELLIPSIS.length() : limit;
    int used = 0;
    int end = 0;
    for (int i = 0; i < text.length();) {
      int cp = text.codePointAt(i);
      int size = utf8Length(cp);
      if (used + size > budget) {
        break;
      }
      used += size;
      i += Character.charCount(cp);
      end = i;
    }
    String cut = text.substring(0, end);
    while (cut.endsWith(" ")) {
      cut = cut.substring(0, cut.length() - 1);
    }
    if (limit > ELLIPSIS.length()) {
      cut = cut + ELLIPSIS;
    }
    return cut.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * UTF-8 bytes of the folded text as one line of the watch's screen: at most {@code maxChars}
   * characters and at most {@code maxBytes} bytes, ending in "..." when something had to be left out.
   */
  static byte[] line(String in, int maxChars, int maxBytes) {
    String text = fold(in);
    if (maxChars <= 0 || maxBytes <= 0) {
      return new byte[0];
    }
    byte[] all = text.getBytes(StandardCharsets.UTF_8);
    if (text.codePointCount(0, text.length()) <= maxChars && all.length <= maxBytes) {
      return all;
    }
    boolean dots = maxChars > ELLIPSIS.length() && maxBytes > ELLIPSIS.length();
    int charBudget = dots ? maxChars - ELLIPSIS.length() : maxChars;
    int byteBudget = dots ? maxBytes - ELLIPSIS.length() : maxBytes;
    int usedChars = 0;
    int usedBytes = 0;
    int end = 0;
    for (int i = 0; i < text.length();) {
      int cp = text.codePointAt(i);
      int size = utf8Length(cp);
      if (usedChars + 1 > charBudget || usedBytes + size > byteBudget) {
        break;
      }
      usedChars++;
      usedBytes += size;
      i += Character.charCount(cp);
      end = i;
    }
    String cut = text.substring(0, end);
    while (cut.endsWith(" ")) {
      cut = cut.substring(0, cut.length() - 1);
    }
    if (dots) {
      cut = cut + ELLIPSIS;
    }
    return cut.getBytes(StandardCharsets.UTF_8);
  }

  /** Four bytes, most significant first: the format the watch expects for positions and lengths. */
  static byte[] int32(long value) {
    long v = Math.max(0, Math.min(value, Integer.MAX_VALUE));
    return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
  }

  private static boolean drawable(int cp) {
    return (cp >= 0x20 && cp <= 0x7e) || (cp >= 0x410 && cp <= 0x44f) || cp == 0xb0;
  }

  /** Appends the watch-safe form of one character. Returns false if there is no usable form. */
  private static boolean appendFolded(StringBuilder out, int cp) {
    if (drawable(cp)) {
      out.appendCodePoint(cp);
      return true;
    }
    if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
      out.append(' ');
      return true;
    }
    String special = special(cp);
    if (special != null) {
      out.append(special);
      return true;
    }
    if (invisible(cp)) {
      return true;
    }
    String parts = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKD);
    boolean any = false;
    for (int i = 0; i < parts.length();) {
      int part = parts.codePointAt(i);
      i += Character.charCount(part);
      if (drawable(part)) {
        out.appendCodePoint(part);
        any = true;
      } else {
        String partSpecial = special(part);
        if (partSpecial != null) {
          out.append(partSpecial);
          any = true;
        }
      }
    }
    return any;
  }

  /** Combining marks, joiners and other characters that take no space of their own. */
  private static boolean invisible(int cp) {
    switch (Character.getType(cp)) {
      case Character.NON_SPACING_MARK:
      case Character.ENCLOSING_MARK:
      case Character.COMBINING_SPACING_MARK:
      case Character.FORMAT:
      case Character.CONTROL:
        return true;
      default:
        return false;
    }
  }

  private static String special(int cp) {
    switch (cp) {
      case 0x131: // dotless i
        return "i";
      case 0x141:
        return "L";
      case 0x142:
        return "l";
      case 0xd8:
        return "O";
      case 0xf8:
        return "o";
      case 0x110:
      case 0xd0:
        return "D";
      case 0x111:
      case 0xf0:
        return "d";
      case 0x126:
        return "H";
      case 0x127:
        return "h";
      case 0xdf:
        return "ss";
      case 0x1e9e:
        return "SS";
      case 0xc6:
        return "AE";
      case 0xe6:
        return "ae";
      case 0x152:
        return "OE";
      case 0x153:
        return "oe";
      case 0xde:
        return "Th";
      case 0xfe:
        return "th";
      case 0x2018:
      case 0x2019:
      case 0x201a:
      case 0x201b:
      case 0x2032:
      case 0x2bc:
      case 0xb4:
        return "'";
      case 0x201c:
      case 0x201d:
      case 0x201e:
      case 0x201f:
      case 0x2033:
      case 0xab:
      case 0xbb:
        return "\"";
      case 0x2010:
      case 0x2011:
      case 0x2012:
      case 0x2013:
      case 0x2014:
      case 0x2015:
      case 0x2212:
      case 0x2022:
      case 0xb7:
      case 0x2219:
        return "-";
      case 0x2026:
        return "...";
      case 0xd7:
        return "x";
      case 0xf7:
      case 0x2044:
      case 0x2215:
        return "/";
      case 0xbf:
        return "?";
      case 0xa1:
        return "!";
      case 0xa9:
        return "(c)";
      case 0xae:
        return "(R)";
      // Cyrillic letters outside the block the watch font carries.
      case 0x401:
        return "Е";
      case 0x451:
        return "е";
      case 0x404:
        return "Е";
      case 0x454:
        return "е";
      case 0x406:
      case 0x407:
        return "I";
      case 0x456:
      case 0x457:
        return "i";
      case 0x490:
        return "Г";
      case 0x491:
        return "г";
      default:
        return null;
    }
  }

  private static String collapseSpaces(StringBuilder in) {
    StringBuilder out = new StringBuilder(in.length());
    boolean lastSpace = true;
    for (int i = 0; i < in.length(); i++) {
      char c = in.charAt(i);
      if (c == ' ') {
        if (!lastSpace) {
          out.append(c);
        }
        lastSpace = true;
      } else {
        out.append(c);
        lastSpace = false;
      }
    }
    int end = out.length();
    while (end > 0 && out.charAt(end - 1) == ' ') {
      end--;
    }
    return out.substring(0, end);
  }

  private static int utf8Length(int cp) {
    if (cp < 0x80) {
      return 1;
    }
    if (cp < 0x800) {
      return 2;
    }
    if (cp < 0x10000) {
      return 3;
    }
    return 4;
  }
}
