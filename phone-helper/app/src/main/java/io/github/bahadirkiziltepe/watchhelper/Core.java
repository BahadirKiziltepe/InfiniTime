package io.github.bahadirkiziltepe.watchhelper;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one long-lived object of the app. It ties Spotify's player to the watch link and lives as
 * long as the process does, whether or not the screen is open.
 */
final class Core implements SpotifyWatcher.Listener, WatchLink.Listener, Updater.Listener {
  // Button codes the watch sends. The first six are the stock InfiniTime ones.
  static final int EVENT_PLAY = 0x00;
  static final int EVENT_PAUSE = 0x01;
  static final int EVENT_NEXT = 0x03;
  static final int EVENT_PREVIOUS = 0x04;
  static final int EVENT_VOLUME_UP = 0x05;
  static final int EVENT_VOLUME_DOWN = 0x06;
  // The watch's bottom row of buttons (watch build 4 and newer).
  static final int EVENT_SHUFFLE = 0x10;
  static final int EVENT_REPEAT = 0x11;
  static final int EVENT_ADD = 0x12;
  // The watch's call screen (watch build 6 and newer).
  static final int EVENT_CALL_ANSWER = 0x20;
  static final int EVENT_CALL_DECLINE = 0x21;
  static final int EVENT_CALL_VOLUME_UP = 0x22;
  static final int EVENT_CALL_VOLUME_DOWN = 0x23;
  // Holding the call screen during a call (watch build 7 and newer).
  static final int EVENT_CALL_HANG_UP = 0x24;

  /** Android announces every volume change under this name. It is not in the public list of names. */
  private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
  private static final int UNKNOWN_LEVEL = 0xff;
  static final int EVENT_OPEN = 0xe0;

  private static final String GADGETBRIDGE = "nodomain.freeyourgadget.gadgetbridge";
  private static final String FROM_WATCH = "watch button";

  private static Core instance;

  static synchronized Core get(Context context) {
    if (instance == null) {
      instance = new Core(context.getApplicationContext());
    }
    return instance;
  }

  final Context app;
  final Handler main = new Handler(Looper.getMainLooper());
  final EventLog log = new EventLog();
  final SpotifyWatcher spotify;
  final WatchLink watch;
  final Updater updater;
  final NotificationRelay notifications;
  final CallWatcher calls;
  private boolean watchWasReady;
  /** The phone's battery in percent, as last announced by Android. */
  private int phoneBattery = UNKNOWN_LEVEL;

  private final List<Runnable> screens = new ArrayList<>();
  private boolean screenUpdatePosted;

  private Core(Context app) {
    this.app = app;
    this.spotify = new SpotifyWatcher(app, main, log, this);
    this.watch = new WatchLink(app, main, log, this);
    this.updater = new Updater(app, main, log, watch, buildNumber(), this);
    this.notifications = new NotificationRelay(main, log, watch);
    this.calls = new CallWatcher(app, main, log, watch, this::changed);
    log.add("app", "started, version " + version());

    // Only the system can send these broadcasts, so receiving them from outside the app is safe.
    BroadcastReceiver bluetoothSwitch = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
        if (state == BluetoothAdapter.STATE_ON) {
          log.add("watch", "Bluetooth was turned on");
          watch.onBluetoothOn();
        } else if (state == BluetoothAdapter.STATE_OFF) {
          log.add("watch", "Bluetooth was turned off");
          watch.onBluetoothOff();
        }
      }
    };
    app.registerReceiver(bluetoothSwitch, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        Context.RECEIVER_EXPORTED);

    BroadcastReceiver phoneClock = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_TIME_TICK.equals(intent.getAction())) {
          watch.clockTick();
        } else {
          log.add("watch", "the phone's time or time zone changed");
          watch.syncClock();
        }
      }
    };
    IntentFilter clock = new IntentFilter();
    clock.addAction(Intent.ACTION_TIME_TICK);
    clock.addAction(Intent.ACTION_TIME_CHANGED);
    clock.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    app.registerReceiver(phoneClock, clock, Context.RECEIVER_EXPORTED);

    // The watch shows the phone's media volume and battery, so both are passed on whenever they change.
    BroadcastReceiver levels = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        noteBattery(intent);
        sendLevels();
      }
    };
    IntentFilter levelChanges = new IntentFilter();
    levelChanges.addAction(VOLUME_CHANGED);
    levelChanges.addAction(Intent.ACTION_BATTERY_CHANGED);
    try {
      // The battery announcement is a standing one: registering hands over the latest right away.
      noteBattery(app.registerReceiver(levels, levelChanges, Context.RECEIVER_EXPORTED));
    } catch (RuntimeException e) {
      log.add("app", "cannot follow the phone's volume and battery: " + e);
    }
    sendLevels();
  }

  /** Starts whatever can be started. Called when the screen opens and when Android wakes the app. */
  void wake() {
    spotify.start();
    watch.start();
    calls.start();
  }

  void onListenerConnected() {
    log.add("app", "notification access is active");
    wake();
    // Start from what the phone is showing right now.
    notifications.sync(MediaListenerService.showing(), MediaListenerService.ranking());
    StatusBarNotification[] showing = MediaListenerService.showing();
    if (showing != null) {
      for (StatusBarNotification sbn : showing) {
        if (sbn != null) {
          calls.onPosted(sbn);
        }
      }
    }
    changed();
  }

  void onNotificationPosted(StatusBarNotification sbn, NotificationListenerService.RankingMap ranking) {
    calls.onPosted(sbn);
    if (NotificationRelay.relays(sbn.getPackageName())) {
      notifications.onPosted(sbn, ranking);
      changed();
    }
  }

  void onNotificationRemoved(StatusBarNotification sbn) {
    calls.onRemoved(sbn);
    if (NotificationRelay.relays(sbn.getPackageName())) {
      notifications.onRemoved(sbn);
      changed();
    }
  }

  void onListenerDisconnected() {
    log.add("app", "notification access was switched off");
    spotify.stop();
  }

  boolean hasNotificationAccess() {
    NotificationManager notifications = app.getSystemService(NotificationManager.class);
    return notifications != null
        && notifications.isNotificationListenerAccessGranted(new ComponentName(app, MediaListenerService.class));
  }

  /** True when Android has been told not to put this app to sleep to save battery. */
  boolean mayRunInBackground() {
    PowerManager power = app.getSystemService(PowerManager.class);
    return power != null && power.isIgnoringBatteryOptimizations(app.getPackageName());
  }

  /** Two apps acting on the same watch buttons would do everything twice. */
  boolean gadgetbridgeInstalled() {
    try {
      app.getPackageManager().getPackageInfo(GADGETBRIDGE, 0);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  String version() {
    try {
      PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
      return info.versionName + " (build " + info.getLongVersionCode() + ")";
    } catch (Exception e) {
      return "unknown";
    }
  }

  private long buildNumber() {
    try {
      return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode();
    } catch (Exception e) {
      return Long.MAX_VALUE;
    }
  }

  // ---------------------------------------------------------------- Spotify -> watch

  @Override
  public void onSpotifyChanged() {
    if (spotify.attached()) {
      watch.setNowPlaying(spotify.title(), spotify.artist(), spotify.album(), spotify.isPlaying(),
          spotify.currentPositionMs(), spotify.speed(), spotify.durationMs());
      watch.setButtonStates(spotify.shuffleState(), spotify.repeatState(), spotify.savedState());
    } else {
      watch.setNowPlaying("", "", "", false, -1, 1f, 0);
      watch.setButtonStates(ButtonStates.UNKNOWN, ButtonStates.UNKNOWN, ButtonStates.UNKNOWN);
    }
    changed();
  }

  private void noteBattery(Intent intent) {
    if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
      return;
    }
    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
    if (level >= 0 && scale > 0) {
      phoneBattery = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
    }
  }

  /** Tells the watch the phone's media volume, in the phone's own steps, and the phone's battery. */
  private void sendLevels() {
    if (watch == null) {
      return;
    }
    int volume = UNKNOWN_LEVEL;
    int steps = UNKNOWN_LEVEL;
    try {
      AudioManager audio = app.getSystemService(AudioManager.class);
      if (audio != null) {
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      }
    } catch (RuntimeException e) {
      // Shown as unknown on the watch.
    }
    watch.setPhoneLevels(volume, steps, phoneBattery);
  }

  // ---------------------------------------------------------------- watch -> phone

  @Override
  public void onWatchChanged() {
    if (updater != null) {
      updater.onWatchLinkChanged();
    }
    boolean ready = watch != null && watch.isReady();
    if (ready && !watchWasReady && notifications != null && calls != null) {
      // A watch that has just linked may have restarted and forgotten everything.
      calls.onWatchReady();
      notifications.onWatchReady();
    }
    watchWasReady = ready;
    changed();
  }

  @Override
  public void onPhoneMessagesLost() {
    if (notifications != null && calls != null) {
      notifications.resend();
      main.postDelayed(calls::onWatchReady, 500);
    }
  }

  @Override
  public void onWatchEvent(int code) {
    switch (code) {
      case EVENT_PLAY:
      case EVENT_PAUSE:
        // The watch sends "play" or "pause" depending on what it believes is happening. Both mean
        // the same button was pressed, so the phone decides from what is really happening.
        spotify.playPause(FROM_WATCH);
        break;
      case EVENT_NEXT:
        spotify.next(FROM_WATCH);
        break;
      case EVENT_PREVIOUS:
        spotify.previous(FROM_WATCH);
        break;
      case EVENT_VOLUME_UP:
        volume(true);
        break;
      case EVENT_VOLUME_DOWN:
        volume(false);
        break;
      case EVENT_SHUFFLE:
        spotify.press(ActionPicker.SHUFFLE, FROM_WATCH);
        break;
      case EVENT_REPEAT:
        spotify.press(ActionPicker.REPEAT, FROM_WATCH);
        break;
      case EVENT_ADD:
        spotify.saveCurrentSong(FROM_WATCH);
        break;
      case EVENT_CALL_ANSWER:
        calls.answer();
        break;
      case EVENT_CALL_DECLINE:
        calls.decline();
        break;
      case EVENT_CALL_VOLUME_UP:
        calls.volume(true);
        break;
      case EVENT_CALL_VOLUME_DOWN:
        calls.volume(false);
        break;
      case EVENT_CALL_HANG_UP:
        calls.hangUp();
        break;
      default:
        log.add("watch", "unknown button message: " + eventName(code));
        break;
    }
    changed();
  }

  /**
   * One step of the phone's media volume, the same as one press of its volume key, so the watch
   * and the phone's own keys count alike.
   */
  private void volume(boolean up) {
    try {
      AudioManager audio = app.getSystemService(AudioManager.class);
      if (audio != null) {
        int stream = AudioManager.STREAM_MUSIC;
        int before = audio.getStreamVolume(stream);
        audio.adjustStreamVolume(stream, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        log.add("control", FROM_WATCH + ": volume " + (up ? "up" : "down") + " (" + before + " to "
            + audio.getStreamVolume(stream) + " of " + audio.getStreamMaxVolume(stream) + ")");
      }
    } catch (RuntimeException e) {
      log.add("control", "volume change failed: " + e);
    }
    sendLevels();
  }

  static String eventName(int code) {
    switch (code) {
      case EVENT_PLAY:
        return "play";
      case EVENT_PAUSE:
        return "pause";
      case EVENT_NEXT:
        return "next";
      case EVENT_PREVIOUS:
        return "previous";
      case EVENT_VOLUME_UP:
        return "volume up";
      case EVENT_VOLUME_DOWN:
        return "volume down";
      case EVENT_SHUFFLE:
        return "shuffle";
      case EVENT_REPEAT:
        return "repeat";
      case EVENT_ADD:
        return "add";
      case EVENT_CALL_ANSWER:
        return "answer call";
      case EVENT_CALL_DECLINE:
        return "decline call";
      case EVENT_CALL_VOLUME_UP:
        return "call volume up";
      case EVENT_CALL_VOLUME_DOWN:
        return "call volume down";
      case EVENT_CALL_HANG_UP:
        return "hang up";
      case EVENT_OPEN:
        return "music screen opened";
      default:
        return String.format(Locale.ROOT, "code 0x%02x", code);
    }
  }

  // ---------------------------------------------------------------- updates

  @Override
  public void onUpdaterChanged() {
    changed();
  }

  // ---------------------------------------------------------------- screen updates

  void addScreen(Runnable screen) {
    if (!screens.contains(screen)) {
      screens.add(screen);
    }
  }

  void removeScreen(Runnable screen) {
    screens.remove(screen);
  }

  /** Asks open screens to redraw. Many changes in a row cause one redraw. */
  private void changed() {
    if (screenUpdatePosted) {
      return;
    }
    screenUpdatePosted = true;
    main.post(() -> {
      screenUpdatePosted = false;
      for (Runnable screen : new ArrayList<>(screens)) {
        screen.run();
      }
    });
  }
}
