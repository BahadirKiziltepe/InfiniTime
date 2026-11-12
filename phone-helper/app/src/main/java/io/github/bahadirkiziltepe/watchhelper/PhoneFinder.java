package io.github.bahadirkiziltepe.watchhelper;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
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
 * <p>The sound is one the owner picked in the app, or else the phone's alarm sound. It is played
 * the way an alarm clock plays its sound: alarms have their own volume and are not silenced by the
 * sound mode. That volume is turned all the way up for the ringing and put back afterwards, and so
 * is Do Not Disturb if it was on. The phone vibrates as well.
 *
 * <p>A picked sound can be one this app may not open itself (a sound another app added to the
 * phone's ringtones). Android's own ringtone player can, so that one is asked to play it then.
 *
 * <p>It stops when the watch says so, when the phone is unlocked, when the app's screen is opened,
 * or by itself after a minute.
 */
final class PhoneFinder {
  private static final long RING_MS = 60_000;
  private static final long BEEP_EVERY_MS = 1_200;
  private static final int BEEP_MS = 800;
  /** How long Android's ringtone player gets to start before the usual sounds are used instead. */
  private static final long SYSTEM_SOUND_CHECK_MS = 1_500;

  private static final String PREFS = "find-phone";
  private static final String KEY_SOUND = "sound";
  private static final String KEY_SOUND_NAME = "sound_name";

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final SoundMode sound;
  private final Runnable changed;
  private final SharedPreferences prefs;

  private boolean ringing;
  private MediaPlayer player;
  /** The picked sound while Android's own ringtone player plays it. */
  private Ringtone systemSound;
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
  private final Runnable checkSystemSound = this::checkSystemSound;

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
    this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  // ---------------------------------------------------------------- which sound

  /** The sound the owner picked, or null for the phone's alarm sound. */
  Uri chosenSound() {
    String saved = prefs.getString(KEY_SOUND, null);
    try {
      return saved == null || saved.isEmpty() ? null : Uri.parse(saved);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** What the screen calls the sound. */
  String soundName() {
    if (chosenSound() == null) {
      return "the phone’s alarm sound";
    }
    String name = prefs.getString(KEY_SOUND_NAME, "");
    return name.isEmpty() ? "a sound you picked" : name;
  }

  /** Remembers the picked sound; null goes back to the phone's alarm sound. The name is only for the screen. */
  void setSound(Uri picked, String name) {
    if (picked == null) {
      prefs.edit().remove(KEY_SOUND).remove(KEY_SOUND_NAME).apply();
    } else {
      prefs.edit().putString(KEY_SOUND, picked.toString()).putString(KEY_SOUND_NAME, name == null ? "" : name).apply();
    }
    // Which sound it is stays out of the log: a file's name can say more than it should
    log.add("find", picked == null ? "the sound is the phone's alarm sound again" : "a sound was picked");
    changed.run();
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
    main.removeCallbacks(checkSystemSound);
    if (systemSound != null) {
      try {
        systemSound.stop();
      } catch (RuntimeException e) {
        // It stops with the app at the latest.
      }
      systemSound = null;
    }
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

  /** Starts the sound: the picked one, or the alarm sound, or the ringtone, or the notification sound, or plain beeps. */
  private boolean play() {
    Uri chosen = chosenSound();
    if (chosen != null) {
      if (playHere(chosen)) {
        lastSound = "the picked sound";
        return true;
      }
      if (playWithAndroid(chosen)) {
        lastSound = "the picked sound, played by Android";
        return true;
      }
    }
    return playUsual();
  }

  /** The sounds every phone has, in the order they are tried, and beeps if none of them plays. */
  private boolean playUsual() {
    Uri[] sounds = {Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_RINGTONE_URI,
        Settings.System.DEFAULT_NOTIFICATION_URI};
    String[] names = {"alarm sound", "ringtone", "notification sound"};
    for (int i = 0; i < sounds.length; i++) {
      if (playHere(sounds[i])) {
        lastSound = names[i];
        return true;
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

  private static AudioAttributes asAlarm() {
    return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
  }

  /** Plays a sound in this app, over and over. False if there is no such sound, or none the app may read. */
  private boolean playHere(Uri what) {
    MediaPlayer candidate = new MediaPlayer();
    try {
      candidate.setAudioAttributes(asAlarm());
      candidate.setDataSource(app, what);
      candidate.setLooping(true);
      candidate.prepare();
      candidate.start();
      player = candidate;
      return true;
    } catch (IOException | RuntimeException e) {
      candidate.release();
      return false;
    }
  }

  /**
   * Has Android's own ringtone player play a sound this app may not open. Whether it really
   * sounds is only known a moment later; if it does not, the usual sounds take over.
   */
  private boolean playWithAndroid(Uri what) {
    try {
      Ringtone candidate = RingtoneManager.getRingtone(app, what);
      if (candidate == null) {
        return false;
      }
      candidate.setAudioAttributes(asAlarm());
      candidate.setLooping(true);
      candidate.setVolume(1f);
      candidate.play();
      systemSound = candidate;
      main.postDelayed(checkSystemSound, SYSTEM_SOUND_CHECK_MS);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void checkSystemSound() {
    if (!ringing || systemSound == null) {
      return;
    }
    boolean sounding;
    try {
      sounding = systemSound.isPlaying();
    } catch (RuntimeException e) {
      sounding = false;
    }
    if (sounding) {
      return;
    }
    try {
      systemSound.stop();
    } catch (RuntimeException e) {
      // Nothing was playing anyway.
    }
    systemSound = null;
    playUsual();
    log.add("find", "the picked sound could not be played; ringing with " + lastSound + " instead");
    changed.run();
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
        + "volume, also on mute. Sound: " + soundName() + ".";
  }

  /** For the report. */
  String dump() {
    return "Find my phone: sound " + (chosenSound() == null ? "is the alarm sound" : "was picked") + ", rung " + rings
        + " times" + (ringing ? ", ringing now" : "")
        + (lastSound.isEmpty() ? "" : ", last sound: " + lastSound) + (lastEnd.isEmpty() ? "" : ", last end: " + lastEnd);
  }
}
