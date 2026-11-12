package io.github.bahadirkiziltepe.watchhelper;

/**
 * Gets the phone into a sound mode (sound, vibrate or mute) when the watch asks for it, and makes
 * sure of what the phone really did.
 *
 * <p>Asking Android for a ringer mode is one call, but what a phone makes of it differs. Plain
 * Android turns Do Not Disturb on when an app asks for silence, keeps "vibrate" if that is where
 * the phone was, and undoes the silence again when Do Not Disturb is switched back off. Other
 * phones simply mute. So this asks, looks at what happened, tries the next way if the phone is not
 * where it should be, and puts Do Not Disturb back the way the owner had it whenever the phone can
 * hold the mode without it. Whatever the outcome, the watch is told the mode the phone is really in.
 *
 * <p>No Android imports on purpose, so this can be checked on a desktop JVM against phones that
 * behave in each of these ways. The numbers are Android's own (AudioManager.RINGER_MODE_* and
 * NotificationManager.INTERRUPTION_FILTER_*).
 */
final class SoundSwitch {
  static final int MUTE = 0;
  static final int VIBRATE = 1;
  static final int SOUND = 2;
  static final int UNKNOWN = 0xff;

  /** Do Not Disturb is off. */
  static final int FILTER_ALL = 1;
  /** Android could not say whether Do Not Disturb is on. */
  static final int FILTER_UNKNOWN = 0;

  /** The phone, as far as this needs it. */
  interface Phone {
    /** The sound mode the phone is really in: MUTE, VIBRATE or SOUND, or UNKNOWN. */
    int mode();

    /** The state of Do Not Disturb as Android numbers it, or FILTER_UNKNOWN. */
    int filter();

    /** Asks Android for a ringer mode. */
    void ringer(int mode);

    /** Mutes or unmutes the ring volume, as its volume key would. */
    void muteRing(boolean mute);

    /** Puts Do Not Disturb in the given state. */
    void filter(int filter);

    /** Waits until the phone has had time to act on what it was asked. */
    void settle();
  }

  /** What became of a request. */
  static final class Outcome {
    /** The mode the phone is in now. */
    final int mode;
    final boolean reached;
    /**
     * True if Do Not Disturb is on only because this phone does not go silent without it. It is
     * switched off again when the watch asks for another mode.
     */
    final boolean dndIsOurs;
    /** What was done, for the log: numbers only. */
    final String trail;

    Outcome(int mode, boolean reached, boolean dndIsOurs, String trail) {
      this.mode = mode;
      this.reached = reached;
      this.dndIsOurs = dndIsOurs;
      this.trail = trail;
    }
  }

  private SoundSwitch() {
  }

  static String name(int mode) {
    return mode == MUTE ? "mute" : mode == VIBRATE ? "vibrate" : mode == SOUND ? "sound" : "unknown";
  }

  /**
   * Puts the phone in {@code target}. {@code dndWasOurs} says that Do Not Disturb is on because an
   * earlier request needed it (see Outcome.dndIsOurs).
   */
  static Outcome run(Phone phone, int target, boolean dndWasOurs) {
    StringBuilder trail = new StringBuilder();
    int filterBefore = phone.filter();
    note(trail, "start", phone);
    // The state Do Not Disturb should be in afterwards: as the owner had it. One that is only on
    // because of an earlier request of the watch's is not the owner's.
    boolean ours = dndWasOurs && filterBefore != FILTER_ALL && filterBefore != FILTER_UNKNOWN;
    int filterWanted = ours ? FILTER_ALL : filterBefore;

    reach(phone, target, trail);

    boolean oursNow = false;
    if (filterWanted != FILTER_UNKNOWN && phone.filter() != FILTER_UNKNOWN && phone.filter() != filterWanted) {
      if (phone.mode() == target) {
        // Do Not Disturb changed along the way. Put it back, if the phone keeps the mode without that.
        phone.filter(filterWanted);
        phone.settle();
        note(trail, "dnd back", phone);
        if (phone.mode() != target) {
          // It does not: on this phone the mode and that state of Do Not Disturb come together.
          // The mode is what was asked for.
          reach(phone, target, trail);
          oursNow = phone.mode() == target && filterWanted == FILTER_ALL && phone.filter() != FILTER_ALL
              && phone.filter() != FILTER_UNKNOWN;
        }
      } else {
        // The mode was not reached. Do Not Disturb is not left changed for nothing.
        phone.filter(filterWanted);
        phone.settle();
        note(trail, "dnd back", phone);
      }
    }
    int mode = phone.mode();
    // Do Not Disturb that is still on for an earlier request stays the watch's to switch off
    boolean stillOurs = oursNow || (ours && phone.filter() != FILTER_ALL && phone.filter() != FILTER_UNKNOWN);
    return new Outcome(mode, mode == target, stillOurs, trail.toString());
  }

  /** Asks for the mode the plain way, and if the phone is not there afterwards, the other ways there are. */
  private static void reach(Phone phone, int target, StringBuilder trail) {
    if (phone.mode() == target) {
      return;
    }
    phone.ringer(target);
    phone.settle();
    note(trail, "asked " + name(target), phone);
    if (phone.mode() == target) {
      return;
    }
    if (target == MUTE && phone.mode() == VIBRATE) {
      // Plain Android stays on vibrate when silence is asked for from there. From "sound" it goes silent.
      phone.ringer(SOUND);
      phone.settle();
      phone.ringer(MUTE);
      phone.settle();
      note(trail, "by way of sound", phone);
    } else if (target == VIBRATE && phone.mode() == SOUND) {
      phone.muteRing(true);
      phone.settle();
      note(trail, "ring muted", phone);
    } else if (target == SOUND) {
      phone.muteRing(false);
      phone.settle();
      note(trail, "ring unmuted", phone);
    }
  }

  private static void note(StringBuilder trail, String step, Phone phone) {
    if (trail.length() > 0) {
      trail.append("; ");
    }
    trail.append(step).append(": ").append(name(phone.mode())).append(", dnd ").append(phone.filter());
  }
}
