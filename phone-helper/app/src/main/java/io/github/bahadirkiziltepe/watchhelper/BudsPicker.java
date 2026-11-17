package io.github.bahadirkiziltepe.watchhelper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tells the broadcasts of the user's own earbuds from those of anybody else's nearby.
 *
 * <p>A battery broadcast (see {@link BudsReading}) carries no name and no lasting address: only the
 * phone's own pairing software holds the key that proves whose earbuds they are, and it does not
 * share it. So this goes by what an outsider can know:
 *
 * <ol>
 *   <li>The broadcast comes from the very Bluetooth address of a headset that is connected to
 *       this phone. That is certain. Not all earbuds broadcast from that address.
 *   <li>Otherwise: earbuds broadcast their batteries when their case is opened or closed, and the
 *       user's own connect to this phone, or disconnect from it, within seconds of that. So a
 *       broadcast is taken for theirs if it was heard within twenty seconds of such a moment, was
 *       the strongest one around and not faint, and its levels do not contradict the one number
 *       the phone has for the pair. If two different sets are about equally strong at that
 *       moment, neither is taken.
 *   <li>Once a sender has been taken, what it sends is followed for as long as the headset stays
 *       connected, and for a short while after. Earbuds change the address they broadcast from
 *       every so often; a new sender is taken over while the headset is connected if it is
 *       strong, shows the same case level and nearly the same earbud levels as the last reading,
 *       and agrees with the phone's number.
 * </ol>
 *
 * <p>The second way is a judgement, and can in principle pick a stranger's earbuds that happened
 * to open their case next to this phone in the same seconds. What is at stake is one battery
 * number being wrong until the user's own case is next opened.
 *
 * <p>No Android in here, so it can be tested on any computer. Times are in milliseconds of any
 * clock that does not jump.
 */
final class BudsPicker {
  /** How close to a connect or disconnect a broadcast has to be heard. */
  static final long WINDOW_MS = 20_000;
  /** Fainter than this is somebody else's, or too far away to be sure of. In dBm. */
  static final int FAINTEST = -80;
  /** Another set this close in strength, with other levels, makes it a toss-up. In dB. */
  private static final int CLEARLY_STRONGER = 8;

  private static final class Seen {
    String address;
    int strength;
    long at;
    BudsReading reading;
  }

  /** What was heard in the last twenty seconds, one entry per sender. */
  private final List<Seen> recent = new ArrayList<>();
  private String taken;
  /** The last reading that was taken for the user's. */
  private BudsReading takenReading;
  private boolean connected;
  /** The last time the user's headset connected or disconnected. */
  private long momentAt = Long.MIN_VALUE / 4;

  // For the report
  int heard;
  int certain;
  int judged;
  int followed;
  int takenOver;
  int refusedOther;
  int refusedTiming;
  int refusedFaint;
  int refusedTossUp;
  int refusedLevels;
  /** Why the last broadcast that was not taken was not, in a few words. */
  String lastRefusal = "";

  /**
   * A battery broadcast was heard. {@code sameAddress} says that it came from the address of a
   * headset connected to this phone; {@code pair} is the phone's one number for that headset, or
   * -1. Returns true if the reading is the user's earbuds'.
   */
  boolean heard(long now, String address, int strength, BudsReading reading, boolean sameAddress, int pair) {
    heard++;
    remember(now, address, strength, reading);
    if (sameAddress) {
      certain++;
      taken = address;
      takenReading = reading;
      return true;
    }
    boolean nearMoment = now - momentAt <= WINDOW_MS;
    if (address.equals(taken) && (connected || nearMoment)) {
      followed++;
      takenReading = reading;
      return true;
    }
    if (!nearMoment) {
      if (connected && continues(strength, reading, pair)) {
        takenOver++;
        taken = address;
        takenReading = reading;
        return true;
      }
      refusedTiming++;
      lastRefusal = "not heard near a moment your earbuds connected or disconnected";
      return false;
    }
    Seen best = judge(now, pair);
    if (best != null && !best.address.equals(address)) {
      refusedOther++;
      lastRefusal = "another set of earbuds was nearer";
    }
    return best != null && best.address.equals(address);
  }

  /**
   * True if a broadcast from a sender not seen before carries on where the last reading left off:
   * the user's earbuds under a new address.
   */
  private boolean continues(int strength, BudsReading reading, int pair) {
    BudsReading last = takenReading;
    return last != null && pair >= 0 && strength >= FAINTEST + 10
        && (reading.box < 0 || last.box < 0 || reading.box == last.box)
        && close(reading.left, last.left) && close(reading.right, last.right)
        && BudsReading.fits(pair, reading.left, reading.right);
  }

  private static boolean close(int now, int before) {
    return now < 0 || before < 0 || Math.abs(now - before) <= 10;
  }

  /**
   * The user's headset connected or disconnected. Returns what was heard just before that and is
   * now taken for theirs: the case had been opened a moment earlier. Null if nothing was.
   */
  BudsReading moment(long now, boolean nowConnected, int pair) {
    connected = nowConnected;
    momentAt = now;
    Seen best = judge(now, pair);
    return best == null ? null : best.reading;
  }

  /** The reading was wrong after all: forget the sender. */
  void letGo() {
    taken = null;
    takenReading = null;
  }

  private Seen judge(long now, int pair) {
    prune(now);
    Seen best = null;
    for (Seen seen : recent) {
      if (best == null || seen.strength > best.strength) {
        best = seen;
      }
    }
    if (best == null) {
      return null;
    }
    if (best.strength < FAINTEST) {
      refusedFaint++;
      lastRefusal = "too faint to be sure it was yours";
      return null;
    }
    for (Seen seen : recent) {
      if (seen != best && seen.strength >= best.strength - CLEARLY_STRONGER && !seen.reading.sameLevels(best.reading)) {
        refusedTossUp++;
        lastRefusal = "two different sets of earbuds were about equally near";
        return null;
      }
    }
    if (!BudsReading.fits(pair, best.reading.left, best.reading.right)) {
      refusedLevels++;
      lastRefusal = "its levels did not match the number the phone has for your earbuds";
      return null;
    }
    if (!best.address.equals(taken)) {
      judged++;
      taken = best.address;
    }
    takenReading = best.reading;
    return best;
  }

  private void remember(long now, String address, int strength, BudsReading reading) {
    prune(now);
    for (Seen seen : recent) {
      if (seen.address.equals(address)) {
        seen.strength = strength;
        seen.at = now;
        seen.reading = reading;
        return;
      }
    }
    Seen seen = new Seen();
    seen.address = address;
    seen.strength = strength;
    seen.at = now;
    seen.reading = reading;
    recent.add(seen);
  }

  private void prune(long now) {
    for (Iterator<Seen> each = recent.iterator(); each.hasNext();) {
      if (now - each.next().at > WINDOW_MS) {
        each.remove();
      }
    }
  }

  /** For the report. */
  String dump() {
    return "Battery broadcasts from earbuds heard: " + heard + " (from your headset's own address: " + certain
        + ", taken as yours at a connect or disconnect: " + judged + ", followed after that: " + (followed + takenOver)
        + ", not taken: " + (refusedTiming + refusedFaint + refusedTossUp + refusedLevels + refusedOther) + ")"
        + (lastRefusal.isEmpty() ? "" : "\nLast one not taken: " + lastRefusal);
  }
}
