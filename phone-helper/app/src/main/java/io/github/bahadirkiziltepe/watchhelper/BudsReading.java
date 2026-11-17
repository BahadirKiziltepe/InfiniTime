package io.github.bahadirkiziltepe.watchhelper;

/**
 * What a pair of earbuds says about its batteries in its own broadcast: the left one, the right
 * one and the case.
 *
 * <p>Earbuds that pair the "Fast Pair" way keep broadcasting a short Bluetooth message while they
 * are switched on, so that phones can recognise them. When their case is opened or closed with
 * both of them inside, that message carries three more bytes for a while: the battery of each
 * earbud and of the case, each with a mark for "charging". Google's description of it:
 * developers.google.com/nearby/fast-pair/specifications/extensions/batterynotification
 *
 * <p>The message does not say whose earbuds they are in any way an outsider can read; see
 * {@link BudsPicker} for how the user's own are told from others'.
 *
 * <p>No Android in here, so it can be tested on any computer.
 */
final class BudsReading {
  /** In percent, or -1 where the earbuds did not say. */
  final int left;
  final int right;
  final int box;
  final boolean leftCharging;
  final boolean rightCharging;
  final boolean boxCharging;
  /** True if the earbuds asked for the levels to be shown (case opened), false to be put away (case closed). */
  final boolean show;

  BudsReading(int left, int right, int box, boolean leftCharging, boolean rightCharging, boolean boxCharging,
      boolean show) {
    this.left = left;
    this.right = right;
    this.box = box;
    this.leftCharging = leftCharging;
    this.rightCharging = rightCharging;
    this.boxCharging = boxCharging;
    this.show = show;
  }

  /**
   * Reads the batteries out of what a device broadcasts under the Fast Pair service (0xFE2C).
   * Null if there are none in it: most of the time there are not.
   *
   * <p>The data is one byte of version and flags, then fields that each begin with a byte holding
   * their length (upper half) and their kind (lower half). Kind 3 or 4 with a length of three is
   * the batteries: left, right, case, each as a charging bit and seven bits of percent, where 127
   * stands for unknown. Earbuds that are being paired send three bytes of model number instead,
   * and nothing else.
   */
  static BudsReading parse(byte[] data) {
    if (data == null || data.length < 5) {
      // Too short to hold batteries; three bytes are a model number
      return null;
    }
    int at = 1;
    while (at < data.length) {
      int header = data[at] & 0xff;
      int length = header >> 4;
      int kind = header & 0x0f;
      if (at + 1 + length > data.length) {
        // Not laid out the way this expects. Better nothing than something made up.
        return null;
      }
      if ((kind == 0x03 || kind == 0x04) && length == 3) {
        int left = data[at + 1] & 0xff;
        int right = data[at + 2] & 0xff;
        int box = data[at + 3] & 0xff;
        BudsReading reading = new BudsReading(level(left), level(right), level(box), (left & 0x80) != 0,
            (right & 0x80) != 0, (box & 0x80) != 0, kind == 0x03);
        // Three unknowns say nothing
        return reading.left < 0 && reading.right < 0 && reading.box < 0 ? null : reading;
      }
      at += 1 + length;
    }
    return null;
  }

  private static int level(int raw) {
    int value = raw & 0x7f;
    return value > 100 ? -1 : value;
  }

  boolean sameLevels(BudsReading other) {
    return other != null && left == other.left && right == other.right && box == other.box;
  }

  /**
   * True if the one number the phone has for the pair can come from one of these two earbuds. The
   * phone's number is one earbud's level, mostly rounded up to the next ten, so the earbud is at
   * most ten below it and never above. A little slack on both sides. True too when the phone has
   * no number ({@code pair} below zero): then there is nothing to hold against it.
   */
  static boolean fits(int pair, int left, int right) {
    if (pair < 0) {
      return true;
    }
    return near(pair, left) || near(pair, right);
  }

  private static boolean near(int pair, int bud) {
    return bud >= 0 && bud >= pair - 12 && bud <= pair + 2;
  }

  /** For the log and the report. Battery levels only. */
  String describe() {
    return "left " + part(left, leftCharging) + ", right " + part(right, rightCharging) + ", case "
        + part(box, boxCharging) + (show ? "" : " (case closing)");
  }

  private static String part(int level, boolean charging) {
    return level < 0 ? "not said" : level + " %" + (charging ? " charging" : "");
  }
}
