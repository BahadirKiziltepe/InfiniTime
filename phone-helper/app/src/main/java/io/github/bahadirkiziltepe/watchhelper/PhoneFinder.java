package io.github.bahadirkiziltepe.watchhelper;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import java.io.IOException;

/**
 * Find my phone: makes the phone ring at full volume when the watch asks, also when the phone is
 * on mute or vibrate.
 *
 * <p>The sound is the phone's alarm sound, played the way an alarm clock plays it: alarms have
 * their own volume and are not silenced by the sound mode. That volume is turned all the way up
 * for the ringing and put back afterwards, and so is Do Not Disturb if it was on. The phone
 * vibrates as well.
 *
 * <p>It stops when the watch says so, when the phone is unlocked, when the app's screen is opened,
 * or by itself after a minute.
 */
final class PhoneFinder {
  private static final long RING_MS = 60_000;
  private static final long BEEP_EVERY_MS = 1_200;
  private static final int BEEP_MS = 800;

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final SoundMode sound;
  private final Runnable changed;

  private boolean ringing;
  private MediaPlayer player;
  private ToneGenerator tone;
  private Vibrator vibrating;
  private boolean listening;
  /** The alarm volume before the ringing, or -1 if it was not touched; and what it was turned up to. */
  private int savedVolume = -1;
  private int raisedVolume = -1;
  /** The state of Do Not Disturb before the ringing, or 0 if it was not touched. */
  private int savedFilter;
  private int modeBefore = SoundSwitch.UNKNOWN;
  private long startedAt;
  private int rings;
  private String lastEnd = "";
  private String lastSound = "";

  private final Runnable timeUp = () -> stop("a minute had passed");
  private final Runnable beep = this::beep;

  /** Only the system can send this announcement. */
  private final BroadcastReceiver unlocked = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      stop("the phone was unlocked");
    }
  };

  PhoneFinder(Context app, Handler main, EventLog log, WatchLink watch, SoundMode sound, Runnable changed) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.watch = watch;
    this.sound = sound;
    this.changed = changed;
  }

  boolean ringing() {
    return ringing;
  }

  /** Starts the ringing, or stops it. {@code who} is who asked, for the log. Main thread. */
  void set(boolean ring, String who) {
    if (ring) {
      start(who);
    } else {
      stop(who + " stopped it");
    }
  }

  private void start(String who) {
    if (ringing) {
      return;
    }
    // Without it Android 17 drops the sound and the volume change of an app in the background
    LinkService.ensure(app);
    modeBefore = sound.current();
    AudioManager audio = app.getSystemService(AudioManager.class);
    savedVolume = -1;
    try {
      if (audio != null) {
        int before = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        int loudest = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, loudest, 0);
        savedVolume = before;
        raisedVolume = loudest;
      }
    } catch (RuntimeException e) {
      log.add("find", "could not turn the alarm volume up: " + e.getClass().getSimpleName());
    }
    savedFilter = 0;
    try {
      NotificationManager notifications = app.getSystemService(NotificationManager.class);
      if (notifications != null && notifications.isNotificationPolicyAccessGranted()) {
        int filter = notifications.getCurrentInterruptionFilter();
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
          notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
          savedFilter = filter;
        }
      }
    } catch (RuntimeException e) {
      // Do Not Disturb stays as it is. Alarms usually get through it anyway.
    }
    boolean sounding = play();
    boolean shaking = vibrate();
    if (!sounding && !shaking) {
      restore();
      log.add("find", who + ": the phone could neither ring nor vibrate");
      changed.run();
      return;
    }
    ringing = true;
    rings++;
    startedAt = SystemClock.elapsedRealtime();
    try {
      app.registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED);
      listening = true;
    } catch (RuntimeException e) {
      listening = false;
    }
    main.postDelayed(timeUp, RING_MS);
    watch.setFindRinging(true);
    log.add("find", who + ": the phone rings to be found (" + lastSound + (shaking ? ", vibrating" : "")
        + (LinkService.running() ? "" : ", the foreground service is not running") + ")");
    changed.run();
  }

  /** Stops the ringing and puts back what was changed for it. {@code why} is for the log. Main thread. */
  void stop(String why) {
    if (!ringing) {
      return;
    }
    ringing = false;
    main.removeCallbacks(timeUp);
    main.removeCallbacks(beep);
    if (player != null) {
      try {
        player.stop();
      } catch (RuntimeException e) {
        // It is released all the same.
      }
      player.release();
      player = null;
    }
    if (tone != null) {
      tone.release();
      tone = null;
    }
    if (vibrating != null) {
      try {
        vibrating.cancel();
      } catch (RuntimeException e) {
        // It stops by itself when the app does.
      }
      vibrating = null;
    }
    if (listening) {
      try {
        app.unregisterReceiver(unlocked);
      } catch (RuntimeException e) {
        // Was not registered after all.
      }
      listening = false;
    }
    restore();
    watch.setFindRinging(false);
    lastEnd = why + " after " + (SystemClock.elapsedRealtime() - startedAt) / 1000 + " s";
    log.add("find", "the ringing stopped: " + lastEnd);
    changed.run();
    // On a phone that only goes silent together with Do Not Disturb, lifting Do Not Disturb for
    // the ringing ended the silence too. Put the phone back on mute then.
    final int before = modeBefore;
    if (before == SoundSwitch.MUTE) {
      main.postDelayed(() -> {
        if (!ringing && sound.current() != before) {
          sound.set(before, "after the ringing");
        }
      }, 1_500);
    }
  }

  /** The alarm volume and Do Not Disturb as they were, unless somebody changed them in the meantime. */
  private void restore() {
    try {
      AudioManager audio = app.getSystemService(AudioManager.class);
      if (audio != null && savedVolume >= 0 && audio.getStreamVolume(AudioManager.STREAM_ALARM) == raisedVolume) {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
      }
    } catch (RuntimeException e) {
      log.add("find", "could not put the alarm volume back: " + e.getClass().getSimpleName());
    }
    savedVolume = -1;
    try {
      NotificationManager notifications = app.getSystemService(NotificationManager.class);
      if (notifications != null && savedFilter != 0
          && notifications.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL) {
        notifications.setInterruptionFilter(savedFilter);
      }
    } catch (RuntimeException e) {
      log.add("find", "could not switch Do Not Disturb back on: " + e.getClass().getSimpleName());
    }
    savedFilter = 0;
  }

  /** Starts the sound: the alarm sound, or the ringtone, or the notification sound, or plain beeps. */
  private boolean play() {
    Uri[] sounds = {Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_RINGTONE_URI,
        Settings.System.DEFAULT_NOTIFICATION_URI};
    String[] names = {"alarm sound", "ringtone", "notification sound"};
    for (int i = 0; i < sounds.length; i++) {
      MediaPlayer candidate = new MediaPlayer();
      try {
        candidate.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        candidate.setDataSource(app, sounds[i]);
        candidate.setLooping(true);
        candidate.prepare();
        candidate.start();
        player = candidate;
        lastSound = names[i];
        return true;
      } catch (IOException | RuntimeException e) {
        // No such sound, or none the app may read. Try the next.
        candidate.release();
      }
    }
    try {
      tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
      lastSound = "beeps";
      beep();
      return true;
    } catch (RuntimeException e) {
      tone = null;
      lastSound = "no sound";
      return false;
    }
  }

  private void beep() {
    if (tone == null) {
      return;
    }
    try {
      tone.startTone(ToneGenerator.TONE_SUP_RINGTONE, BEEP_MS);
    } catch (RuntimeException e) {
      return;
    }
    main.postDelayed(beep, BEEP_EVERY_MS);
  }

  private boolean vibrate() {
    try {
      Vibrator vibrator = app.getSystemService(Vibrator.class);
      if (vibrator == null || !vibrator.hasVibrator()) {
        return false;
      }
      vibrator.vibrate(VibrationEffect.createWaveform(new long[] {0, 700, 500}, 0),
          VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
      vibrating = vibrator;
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** One line for the screen. */
  String describe() {
    if (ringing) {
      return "Find my phone: ringing now… It stops when you tap Stop ringing, unlock the phone, or after a minute.";
    }
    return "✓ Find my phone: ready. The button on the watch's settings screen makes this phone ring at full "
        + "volume, also on mute.";
  }

  /** For the report. */
  String dump() {
    return "Find my phone: rung " + rings + " times" + (ringing ? ", ringing now" : "")
        + (lastSound.isEmpty() ? "" : ", last sound: " + lastSound) + (lastEnd.isEmpty() ? "" : ", last end: " + lastEnd);
  }
}
