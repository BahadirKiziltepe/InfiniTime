package io.github.bahadirkiziltepe.watchhelper;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Starts Spotify on the phone when the watch wants music and Spotify is not running, so that the
 * phone does not have to be taken out for it. One thing is tried after the other, and the next one
 * only if the one before did not do it:
 *
 * <ol>
 *   <li>A play button like a headset's. Android hands it to the app that played last, and starts
 *       that app if need be. This is only pressed if that app is Spotify (or Android does not say
 *       which it is): pressed blindly it would wake a video in some other app.
 *   <li>Opening Spotify, the way a tap on its icon does. Android lets an app in the background do
 *       that only if the user has allowed it to "appear on top" of other apps.
 *   <li>Once Spotify is there: play. On its own player if that has turned up, otherwise through
 *       Spotify's web service and with a play button handed to Spotify alone, and that again a few
 *       times, because a Spotify that has only just started does not always listen at once.
 * </ol>
 *
 * After fifteen seconds without success it gives up, and says which step it got stuck at. Until
 * then the watch shows "Starting Spotify..." where the song would be.
 *
 * <p>Everything here runs on the main thread.
 */
final class SpotifyStarter {
  interface Host {
    /** Spotify's own player is there, so its buttons can be pressed. */
    boolean playerOpen();

    boolean playing();

    /** Presses play on Spotify's own player. */
    void pressPlay();

    /** Presses play the way a headset does. Android decides which app that reaches. */
    void headsetPlay();

    /** Hands a play button to Spotify and to nobody else. Only works once Spotify is running. */
    void playButtonToSpotify();

    /** The app a headset button would go to right now: "" if none, null if Android does not say. */
    String headsetTarget();

    /** Asks Spotify's web service to play on the phone, if somebody is logged in there. */
    void playOverWeb();

    /** What the watch should show has changed. */
    void changed();
  }

  /** How long all of it may take. */
  static final long LIMIT_MS = 15_000;
  private static final long TICK_MS = 500;
  /** How long the headset button gets to bring Spotify up before Spotify is opened instead. */
  private static final long KEY_WAIT_MS = 3_000;
  /** Spotify needs a moment after being opened before it listens to anything. */
  private static final long OPEN_SETTLE_MS = 1_500;
  private static final long PLAY_EVERY_MS = 2_500;
  private static final int PLAY_TRIES = 4;
  /** How long the watch says that it did not work. */
  private static final long FAILED_SHOWN_MS = 8_000;

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final Host host;

  private boolean running;
  private boolean wantPlay;
  private long startedAt;
  private boolean keyPressed;
  private boolean opened;
  private long openedAt;
  private boolean openRefused;
  private boolean deviceSeen;
  private int playAsks;
  private long lastPlayAsk;
  private long failedAt = -FAILED_SHOWN_MS;
  private boolean failedForPermission;

  // For the screen and the report
  private int runs;
  private int worked;
  private String lastOutcome = "";

  private final Runnable tick = this::step;

  SpotifyStarter(Context app, Handler main, EventLog log, Host host) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.host = host;
  }

  /** True while Spotify is being started. */
  boolean busy() {
    return running;
  }

  /**
   * Starts Spotify if it is not there. With {@code play} the music is to start as well; without,
   * it is enough that Spotify is running, and whoever asked sends its own command then.
   * {@code who} is for the log.
   */
  void begin(String who, boolean play) {
    if (running) {
      wantPlay |= play;
      return;
    }
    long now = SystemClock.elapsedRealtime();
    running = true;
    wantPlay = play;
    startedAt = now;
    keyPressed = false;
    opened = false;
    openedAt = 0;
    openRefused = false;
    deviceSeen = false;
    playAsks = 0;
    lastPlayAsk = now - PLAY_EVERY_MS;
    failedAt = -FAILED_SHOWN_MS;
    failedForPermission = false;
    runs++;

    String target = host.headsetTarget();
    if (target == null || SpotifyWatcher.SPOTIFY.equals(target)) {
      log.add("start", who + ": Spotify is not running. Step 1: a headset play button"
          + (target == null ? " (Android does not say which app gets it)" : ""));
      keyPressed = true;
      host.headsetPlay();
    } else {
      log.add("start", who + ": Spotify is not running, and a headset play button would go to "
          + (target.isEmpty() ? "no app at all" : target) + ". Step 1 left out.");
      open();
    }
    host.changed();
    main.removeCallbacks(tick);
    main.postDelayed(tick, TICK_MS);
  }

  /** Spotify's player has turned up at Spotify's web service. */
  void deviceSeen() {
    if (running) {
      deviceSeen = true;
    }
  }

  private void step() {
    if (!running) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    long ran = now - startedAt;
    boolean up = host.playerOpen() || deviceSeen;
    if (up && (!wantPlay || host.playing())) {
      finish(true, ran);
      return;
    }
    if (ran >= LIMIT_MS) {
      finish(false, ran);
      return;
    }
    if (!up && !opened && !openRefused && (!keyPressed || ran >= KEY_WAIT_MS)) {
      open();
    }
    boolean settled = up || (opened && now - openedAt >= OPEN_SETTLE_MS);
    if (wantPlay && settled && playAsks < PLAY_TRIES && now - lastPlayAsk >= PLAY_EVERY_MS) {
      playAsks++;
      lastPlayAsk = now;
      if (host.playerOpen()) {
        log.add("start", "step 3: play on Spotify's own player (try " + playAsks + ")");
        host.pressPlay();
      } else {
        log.add("start", "step 3: play through Spotify's web service and with a play button of its own (try "
            + playAsks + ")");
        host.playOverWeb();
        host.playButtonToSpotify();
      }
    }
    main.postDelayed(tick, TICK_MS);
  }

  /** Step 2: opens Spotify the way a tap on its icon does. */
  private void open() {
    if (!mayOpen()) {
      openRefused = true;
      log.add("start", "step 2 left out: the app is not allowed to open other apps (\"Appear on top\" is off)");
      return;
    }
    try {
      Intent launch = app.getPackageManager().getLaunchIntentForPackage(SpotifyWatcher.SPOTIFY);
      if (launch == null) {
        openRefused = true;
        log.add("start", "step 2 left out: Spotify was not found on this phone");
        return;
      }
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      app.startActivity(launch);
      opened = true;
      openedAt = SystemClock.elapsedRealtime();
      log.add("start", "step 2: opened Spotify");
    } catch (RuntimeException e) {
      openRefused = true;
      log.add("start", "step 2 failed: " + e.getClass().getSimpleName());
    }
  }

  private void finish(boolean success, long ran) {
    running = false;
    main.removeCallbacks(tick);
    String steps = (keyPressed ? "headset button" : "no headset button") + (opened ? ", opened Spotify" : "")
        + (playAsks > 0 ? ", asked to play " + playAsks + " time" + (playAsks == 1 ? "" : "s") : "");
    if (success) {
      worked++;
      lastOutcome = "worked after " + (ran + 500) / 1000 + " s (" + steps + ")";
    } else {
      boolean up = host.playerOpen() || deviceSeen;
      String stuck = up ? "Spotify came up but did not start playing"
          : opened ? "Spotify was opened but did not turn up as a player"
          : openRefused && !mayOpen() ? "the headset button did nothing, and the app may not open Spotify"
          : "Spotify did not come up";
      failedForPermission = !up && !opened && !mayOpen();
      failedAt = SystemClock.elapsedRealtime();
      lastOutcome = "did not work: " + stuck + " (" + steps + ")";
      // The note on the watch goes away again by itself
      main.postDelayed(host::changed, FAILED_SHOWN_MS + 200);
    }
    log.add("start", "starting Spotify " + lastOutcome);
    host.changed();
  }

  /** True if Android lets this app open Spotify from the background. */
  boolean mayOpen() {
    try {
      return Settings.canDrawOverlays(app);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * What the watch's main screen says where the song would be, or null for nothing special: the
   * first line and the one under it.
   */
  String[] watchText() {
    if (running) {
      return new String[] {"Starting Spotify...", ""};
    }
    if (SystemClock.elapsedRealtime() - failedAt < FAILED_SHOWN_MS) {
      return new String[] {"Can't start Spotify", failedForPermission ? "Allow in phone app" : "Open it by hand"};
    }
    return null;
  }

  /** A few lines for the screen. */
  String describe() {
    StringBuilder sb = new StringBuilder();
    if (running) {
      sb.append("Starting Spotify…");
    } else if (mayOpen()) {
      sb.append("✓ When Spotify is not running, play on the watch starts it: first with a headset play button, and "
          + "if that does nothing, by opening Spotify.");
    } else {
      sb.append("✗ The app may not open Spotify by itself yet. Until “Appear on top” is allowed, only a headset "
          + "play button is tried, which often does nothing.");
    }
    if (!lastOutcome.isEmpty()) {
      sb.append("\nLast time it ").append(lastOutcome).append('.');
    }
    return sb.toString();
  }

  /** For the report. */
  String dump() {
    String target = host.headsetTarget();
    return "Starting Spotify from the watch: allowed to open it: " + (mayOpen() ? "yes" : "no") + ", tried " + runs
        + " time" + (runs == 1 ? "" : "s") + ", worked " + worked + (lastOutcome.isEmpty() ? "" : ", last time it "
        + lastOutcome) + "\nA headset play button would go to: "
        + (target == null ? "Android does not say" : target.isEmpty() ? "no app" : target);
  }
}
