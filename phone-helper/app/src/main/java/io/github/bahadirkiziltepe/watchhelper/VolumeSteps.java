package io.github.bahadirkiziltepe.watchhelper;

/**
 * Works out the volume one swipe on the watch leads to: a tenth of the way up or down.
 *
 * <p>Phones count volume in their own steps (15 for media on most Samsung phones, as few as 5 for
 * calls), so a tenth is not always a whole number of steps. The volume moves along ten evenly
 * spread stops instead, and always by at least one step.
 *
 * <p>No Android imports on purpose, so this can be checked on a desktop JVM.
 */
final class VolumeSteps {
  private static final int STOPS = 10;

  private VolumeSteps() {
  }

  static int next(int current, int min, int max, boolean up) {
    int range = max - min;
    if (range <= 0) {
      return current;
    }
    int now = Math.max(min, Math.min(max, current));
    int stop = Math.round((now - min) * (float) STOPS / range) + (up ? 1 : -1);
    stop = Math.max(0, Math.min(STOPS, stop));
    int target = min + Math.round(stop * (float) range / STOPS);
    if (up && target <= now) {
      target = now + 1;
    } else if (!up && target >= now) {
      target = now - 1;
    }
    return Math.max(min, Math.min(max, target));
  }
}
