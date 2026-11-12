package io.github.bahadirkiziltepe.watchhelper;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone's sound mode (sound, vibrate, mute) for the button on the watch's settings screen.
 *
 * <p>The watch always shows the mode the phone is really in. This class reads it, tells the watch
 * whenever it changes, whoever changed it, and changes it when the watch asks. How the changing is
 * done, and why it is not one simple call, is in {@link SoundSwitch}.
 *
 * <p>Reading the mode: with Do Not Disturb off, Android's ringer mode for apps is the truth. With
 * Do Not Disturb on, plain Android reports "silent" to apps whatever the sound mode is, so the
 * mode is taken from where Android itself keeps it. That way a phone that is on Do Not Disturb
 * with its ringer on "sound" shows as sound on the watch, not as mute.
 */
final class SoundMode {
  /** Android announces the mode it really keeps under this name. It is not in the public list of names. */
  private static final String INTERNAL_CHANGED = "android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION";
  /** And stores it under this one (Settings.Global.MODE_RINGER). */
  private static final String MODE_SETTING = "mode_ringer";

  /** How long the phone gets to act on a request before its state is read again. */
  private static final long SETTLE_MS = 600;
  /** Android writes the stored mode down half a second after the change. */
  private static final long LOOK_AGAIN_MS = 800;

  private static final String PREFS = "sound-mode";
  private static final String KEY_DND_IS_OURS = "dnd_is_ours";

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final Runnable changed;
  private final AudioManager audio;
  private final NotificationManager notifications;
  private final SharedPreferences prefs;

  private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
    Thread thread = new Thread(work, "sound-mode");
    thread.setDaemon(true);
    return thread;
  });

  private boolean started;
  /** True while a request of the watch's is being carried out: the steps in between are not for the watch. */
  private volatile boolean busy;
  /** The mode the watch was told last. */
  private volatile int shown = SoundSwitch.UNKNOWN;
  private volatile String source = "";
  private volatile String lastTrail = "";
  private volatile String lastRefusal = "";
  private volatile int lastWanted = SoundSwitch.UNKNOWN;
  private volatile boolean lastReached = true;
  private volatile int requests;
  private volatile int reached;

  private final Runnable look = this::look;
  private final Runnable lookAgain = this::look;

  private final SoundSwitch.Phone phone = new SoundSwitch.Phone() {
    @Override
    public int mode() {
      return read();
    }

    @Override
    public int filter() {
      return readFilter();
    }

    @Override
    public void ringer(int mode) {
      try {
        if (audio != null) {
          audio.setRingerMode(mode);
        }
      } catch (RuntimeException e) {
        // Android refuses when the change would touch Do Not Disturb and the app may not do that
        lastRefusal = e.getClass().getSimpleName();
      }
    }

    @Override
    public void muteRing(boolean mute) {
      try {
        if (audio != null) {
          audio.adjustStreamVolume(AudioManager.STREAM_RING, mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        }
      } catch (RuntimeException e) {
        lastRefusal = e.getClass().getSimpleName();
      }
    }

    @Override
    public void filter(int filter) {
      try {
        if (notifications != null && notifications.isNotificationPolicyAccessGranted()) {
          notifications.setInterruptionFilter(filter);
        }
      } catch (RuntimeException e) {
        lastRefusal = e.getClass().getSimpleName();
      }
    }

    @Override
    public void settle() {
      try {
        Thread.sleep(SETTLE_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  };

  SoundMode(Context app, Handler main, EventLog log, WatchLink watch, Runnable changed) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.watch = watch;
    this.changed = changed;
    this.audio = app.getSystemService(AudioManager.class);
    this.notifications = app.getSystemService(NotificationManager.class);
    this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** Starts following the phone's sound mode. Safe to call again and again. */
  void start() {
    if (!started) {
      started = true;
      // Only the system can send these announcements. They are taken as a hint to look, no more.
      BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          lookSoon();
        }
      };
      IntentFilter filter = new IntentFilter();
      filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
      filter.addAction(INTERNAL_CHANGED);
      filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
      try {
        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
      } catch (RuntimeException e) {
        log.add("sound", "cannot follow the phone's sound mode: " + e.getClass().getSimpleName());
      }
      try {
        app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(MODE_SETTING), false,
            new ContentObserver(main) {
              @Override
              public void onChange(boolean selfChange) {
                lookSoon();
              }
            });
      } catch (RuntimeException e) {
        // The announcements above do the same job.
      }
    }
    look();
  }

  private void lookSoon() {
    main.removeCallbacks(look);
    main.post(look);
    main.removeCallbacks(lookAgain);
    main.postDelayed(lookAgain, LOOK_AGAIN_MS);
  }

  /** Reads the mode and tells the watch. Main thread. */
  private void look() {
    if (busy) {
      return;
    }
    int mode = read();
    if (mode != shown) {
      shown = mode;
      log.add("sound", "the phone is on " + SoundSwitch.name(mode));
      changed.run();
    }
    watch.setSoundMode(mode);
  }

  /** The mode the phone is in, as the watch shows it. */
  int current() {
    return shown;
  }

  /** May the app switch Do Not Disturb? Android wants that for going to mute and back. */
  boolean mayChangeDoNotDisturb() {
    try {
      return notifications != null && notifications.isNotificationPolicyAccessGranted();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Puts the phone in a mode (SoundSwitch.MUTE, VIBRATE or SOUND) because the watch asked for it. */
  void set(final int target, final String who) {
    if (target != SoundSwitch.MUTE && target != SoundSwitch.VIBRATE && target != SoundSwitch.SOUND) {
      return;
    }
    requests++;
    LinkService.ensure(app);
    worker.execute(() -> {
      busy = true;
      SoundSwitch.Outcome outcome = null;
      lastRefusal = "";
      try {
        outcome = SoundSwitch.run(phone, target, prefs.getBoolean(KEY_DND_IS_OURS, false));
        prefs.edit().putBoolean(KEY_DND_IS_OURS, outcome.dndIsOurs).apply();
      } catch (RuntimeException e) {
        log.add("sound", who + ": changing the sound mode failed: " + e.getClass().getSimpleName());
      } finally {
        busy = false;
      }
      lastWanted = target;
      lastReached = outcome != null && outcome.reached;
      if (outcome != null) {
        lastTrail = outcome.trail;
        if (outcome.reached) {
          reached++;
        }
        log.add("sound", who + ": " + SoundSwitch.name(target) + (outcome.reached ? "" : " not reached, the phone is on "
            + SoundSwitch.name(outcome.mode)) + (outcome.dndIsOurs ? ", with Do Not Disturb (this phone needs it for that)" : "")
            + (lastRefusal.isEmpty() ? "" : ", Android refused once (" + lastRefusal + ")")
            + (outcome.reached || LinkService.running() ? "" : ", the foreground service is not running")
            + " [" + outcome.trail + "]");
      }
      main.post(() -> {
        look();
        changed.run();
      });
    });
  }

  private int readFilter() {
    try {
      return notifications == null ? SoundSwitch.FILTER_UNKNOWN : notifications.getCurrentInterruptionFilter();
    } catch (RuntimeException e) {
      return SoundSwitch.FILTER_UNKNOWN;
    }
  }

  private int external() {
    try {
      return audio == null ? -1 : audio.getRingerMode();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private int announced() {
    try {
      Intent last = app.registerReceiver(null, new IntentFilter(INTERNAL_CHANGED), Context.RECEIVER_EXPORTED);
      return last == null ? -1 : last.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private int stored() {
    try {
      return Settings.Global.getInt(app.getContentResolver(), MODE_SETTING, -1);
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private static boolean valid(int mode) {
    return mode == SoundSwitch.MUTE || mode == SoundSwitch.VIBRATE || mode == SoundSwitch.SOUND;
  }

  /** The sound mode the phone is really in; see the top of this class. */
  private int read() {
    int forApps = external();
    if (readFilter() == SoundSwitch.FILTER_ALL && valid(forApps)) {
      source = "the ringer mode";
      return forApps;
    }
    int kept = announced();
    if (valid(kept)) {
      source = "Android's own announcement";
      return kept;
    }
    kept = stored();
    if (valid(kept)) {
      source = "Android's stored mode";
      return kept;
    }
    source = "the ringer mode";
    return valid(forApps) ? forApps : SoundSwitch.UNKNOWN;
  }

  /** One line for the screen. */
  String describe() {
    int mode = shown;
    if (mode == SoundSwitch.UNKNOWN) {
      return "Phone sound: Android does not say which sound mode the phone is in.";
    }
    String now = mode == SoundSwitch.SOUND ? "Sound" : mode == SoundSwitch.VIBRATE ? "Vibrate" : "Mute";
    if (!lastReached && lastWanted != SoundSwitch.UNKNOWN && lastWanted != mode) {
      return "✗ Phone sound: " + now + ". The watch asked for " + SoundSwitch.name(lastWanted)
          + " and the phone did not go there. Send the report so this can be looked into.";
    }
    return "✓ Phone sound: " + now + ". The sound button on the watch's settings screen shows this and changes it.";
  }

  /** For the report: numbers only. */
  String dump() {
    return "Sound mode shown on the watch: " + SoundSwitch.name(shown) + " (read from " + source + ")\n"
        + "Ringer mode for apps " + external() + ", announced " + announced() + ", stored " + stored()
        + ", Do Not Disturb " + readFilter() + " (1 is off), may change it: " + (mayChangeDoNotDisturb() ? "yes" : "no")
        + ", on because of the watch: " + (prefs.getBoolean(KEY_DND_IS_OURS, false) ? "yes" : "no") + "\n"
        + "Changes asked for by the watch: " + requests + ", reached: " + reached
        + (lastTrail.isEmpty() ? "" : "\nLast change: " + lastTrail)
        + (lastRefusal.isEmpty() ? "" : "\nLast refusal: " + lastRefusal);
  }
}
