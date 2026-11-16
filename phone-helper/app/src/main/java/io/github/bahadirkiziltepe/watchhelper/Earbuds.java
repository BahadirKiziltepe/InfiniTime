package io.github.bahadirkiziltepe.watchhelper;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The battery of the earbuds or headphones that are connected to the phone, for the watch's main
 * screen.
 *
 * <p>Earbuds tell the phone how full they are while they are connected to it, usually in steps of
 * ten. Android keeps that one number (it is the one in the phone's Bluetooth settings) and
 * announces it whenever it changes. Nothing can ask earbuds that are put away: in their case they
 * are switched off as far as the phone is concerned. So the watch gets the number while they are
 * connected and sharing it, exactly as the phone has it, and nothing while they are not.
 *
 * <p>Which devices count: anything connected to the phone for music or for calls that Bluetooth
 * calls an audio device. If more than one shares a battery, the one heard from last is shown.
 *
 * <p>Neither the name nor the address of a device is written to the log or the report.
 *
 * <p>Everything here runs on the main thread.
 */
@SuppressLint("MissingPermission") // Without the Nearby devices permission Android throws, and that is caught everywhere.
final class Earbuds {
  /** Android's announcement of a Bluetooth device's battery, and the number in it. */
  private static final String BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
  private static final String EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";
  static final int UNKNOWN = 0xff;
  /** Asking directly, in case an announcement was missed: soon after connecting, then now and then. */
  private static final long[] ASK_AFTER_MS = {2_000, 6_000, 15_000};
  private static final long ASK_EVERY_MS = 60_000;

  private static final class Headset {
    BluetoothDevice device;
    String name = "";
    boolean calls;
    boolean music;
    /** In percent, or -1 while it has not said. */
    int level = -1;
    long heardAt;
    long connectedAt;
    int asked;
  }

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final Runnable changed;

  private final Map<String, Headset> headsets = new LinkedHashMap<>();
  private boolean started;
  private boolean listening;
  private BluetoothAdapter adapter;
  private String problem = "";

  // For the report: how the numbers arrive on this phone
  private int announced;
  private int answered;
  private boolean askingWorks = true;
  private String askProblem = "";
  private int sent = UNKNOWN;

  private final Runnable ask = this::askAll;

  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
      if (action == null || device == null) {
        return;
      }
      if (BATTERY_CHANGED.equals(action)) {
        announced++;
        onLevel(device, intent.getIntExtra(EXTRA_BATTERY, -1), "announced");
      } else {
        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
        boolean forCalls = BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action);
        if (state == BluetoothProfile.STATE_CONNECTED) {
          onConnected(device, forCalls, !forCalls);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
          onDisconnected(device, forCalls);
        }
      }
    }
  };

  Earbuds(Context app, Handler main, EventLog log, WatchLink watch, Runnable changed) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.watch = watch;
    this.changed = changed;
  }

  /** Starts following the phone's headsets. Safe to call any number of times. */
  void start() {
    if (started) {
      return;
    }
    try {
      BluetoothManager manager = app.getSystemService(BluetoothManager.class);
      adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null) {
        problem = "this phone has no Bluetooth";
        return;
      }
      if (!listening) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BATTERY_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        // Only the system can send these, so receiving them from outside the app is safe.
        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        listening = true;
      }
      // Whatever is connected already. Android answers this a moment later.
      boolean asked = adapter.getProfileProxy(app, alreadyConnected, BluetoothProfile.HEADSET);
      asked |= adapter.getProfileProxy(app, alreadyConnected, BluetoothProfile.A2DP);
      started = true;
      problem = asked ? "" : "Android did not say which headsets are connected";
    } catch (SecurityException e) {
      // The Nearby devices permission is not there yet. Tried again when the app is next woken.
      problem = "waiting for the Nearby devices permission";
    } catch (RuntimeException e) {
      problem = "could not follow the phone's headsets: " + e.getClass().getSimpleName();
      log.add("earbuds", problem);
    }
  }

  private final BluetoothProfile.ServiceListener alreadyConnected = new BluetoothProfile.ServiceListener() {
    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
      try {
        List<BluetoothDevice> devices = proxy.getConnectedDevices();
        for (BluetoothDevice device : devices) {
          onConnected(device, profile == BluetoothProfile.HEADSET, profile == BluetoothProfile.A2DP);
        }
      } catch (RuntimeException e) {
        log.add("earbuds", "could not list the connected headsets: " + e.getClass().getSimpleName());
      }
      try {
        if (adapter != null) {
          adapter.closeProfileProxy(profile, proxy);
        }
      } catch (RuntimeException e) {
        // Nothing to undo.
      }
    }

    @Override
    public void onServiceDisconnected(int profile) {
      // Only asked once.
    }
  };

  /** Bluetooth was switched off: nothing is connected any more. */
  void onBluetoothOff() {
    if (!headsets.isEmpty()) {
      headsets.clear();
      log.add("earbuds", "Bluetooth is off: no headset connected");
      update();
    }
  }

  private void onConnected(BluetoothDevice device, boolean forCalls, boolean forMusic) {
    if (!isAudio(device)) {
      return;
    }
    String key = device.getAddress();
    Headset headset = headsets.get(key);
    if (headset == null) {
      headset = new Headset();
      headset.device = device;
      headset.connectedAt = SystemClock.elapsedRealtime();
      headset.name = nameOf(device);
      headsets.put(key, headset);
      log.add("earbuds", "a headset connected (" + headsets.size() + " connected now)");
    }
    headset.calls |= forCalls;
    headset.music |= forMusic;
    scheduleAsk(ASK_AFTER_MS[0]);
    update();
  }

  private void onDisconnected(BluetoothDevice device, boolean forCalls) {
    Headset headset = headsets.get(device.getAddress());
    if (headset == null) {
      return;
    }
    if (forCalls) {
      headset.calls = false;
    } else {
      headset.music = false;
    }
    if (!headset.calls && !headset.music) {
      headsets.remove(device.getAddress());
      log.add("earbuds", "a headset disconnected (" + headsets.size() + " connected now)");
    }
    update();
  }

  private void onLevel(BluetoothDevice device, int level, String how) {
    Headset headset = headsets.get(device.getAddress());
    if (level < 0 || level > 100) {
      // Android has forgotten the number, which it does when the headset goes away
      if (headset != null && headset.level >= 0) {
        headset.level = -1;
        update();
      }
      return;
    }
    if (headset == null) {
      // The battery was heard of before the connection was. A headset that shares its battery is connected.
      if (!isAudio(device)) {
        return;
      }
      onConnected(device, true, false);
      headset = headsets.get(device.getAddress());
      if (headset == null) {
        return;
      }
    }
    if (headset.level != level) {
      log.add("earbuds", "battery " + how + ": " + level + " %");
    }
    headset.level = level;
    headset.heardAt = SystemClock.elapsedRealtime();
    update();
  }

  /** Music and call devices only: not the watch, a keyboard or a car key. */
  private static boolean isAudio(BluetoothDevice device) {
    try {
      BluetoothClass kind = device.getBluetoothClass();
      return kind != null && kind.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String nameOf(BluetoothDevice device) {
    try {
      String alias = device.getAlias();
      String name = alias != null && !alias.isEmpty() ? alias : device.getName();
      return name == null ? "" : name;
    } catch (RuntimeException e) {
      return "";
    }
  }

  // ---------------------------------------------------------------- asking

  private void scheduleAsk(long inMs) {
    main.removeCallbacks(ask);
    if (askingWorks && !headsets.isEmpty()) {
      main.postDelayed(ask, inMs);
    }
  }

  /**
   * Asks Android for the number it keeps for each connected headset. It is the same number as in
   * the announcements; asking covers an announcement that came before the app was listening.
   */
  private void askAll() {
    if (!askingWorks || headsets.isEmpty()) {
      return;
    }
    long next = ASK_EVERY_MS;
    for (Headset headset : new java.util.ArrayList<>(headsets.values())) {
      int level;
      try {
        Object answer = BluetoothDevice.class.getMethod("getBatteryLevel").invoke(headset.device);
        level = answer instanceof Integer ? (Integer) answer : -1;
      } catch (Exception e) {
        // This Android does not let apps ask. The announcements have to do.
        askingWorks = false;
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        askProblem = cause.getClass().getSimpleName();
        log.add("earbuds", "Android does not answer when asked for a headset's battery (" + askProblem
            + "); waiting for its announcements instead");
        return;
      }
      answered++;
      if (level >= 0 && level <= 100) {
        onLevel(headset.device, level, "asked for");
      }
      if (headset.asked < ASK_AFTER_MS.length - 1) {
        headset.asked++;
        next = Math.min(next, ASK_AFTER_MS[headset.asked]);
      }
    }
    scheduleAsk(next);
  }

  // ---------------------------------------------------------------- for the watch and the screen

  /** The headset whose battery is shown: the one heard from last. Null if none shares one. */
  private Headset shown() {
    Headset best = null;
    for (Headset headset : headsets.values()) {
      if (headset.level >= 0 && (best == null || headset.heardAt >= best.heardAt)) {
        best = headset;
      }
    }
    return best;
  }

  /** The battery the watch shows, in percent, or UNKNOWN for none. */
  int level() {
    Headset headset = shown();
    return headset == null ? UNKNOWN : headset.level;
  }

  private void update() {
    int level = level();
    if (level != sent) {
      sent = level;
      watch.setEarbuds(level);
    }
    changed.run();
  }

  /** A few lines for the screen. The name is shown here and nowhere else. */
  String describe() {
    if (!problem.isEmpty()) {
      return "Earbuds: " + problem + "…";
    }
    if (headsets.isEmpty()) {
      return "Earbuds: none connected. While earbuds or headphones are connected to this phone and share their "
          + "battery, the watch shows it under its own.";
    }
    Headset headset = shown();
    if (headset == null) {
      Headset any = headsets.values().iterator().next();
      return "Earbuds: " + (any.name.isEmpty() ? "connected" : any.name) + ", but they have not shared a battery "
          + "level with the phone… Some only do a moment after connecting, some never.";
    }
    return "✓ Earbuds: " + (headset.name.isEmpty() ? "" : headset.name + ", ") + headset.level
        + " %. The watch shows this number while they are connected.";
  }

  /** For the report. No names and no addresses. */
  String dump() {
    Headset headset = shown();
    return "Headsets connected: " + headsets.size() + ", battery shown on the watch: "
        + (headset == null ? "none" : headset.level + " %") + "\nBattery announcements from Android: " + announced
        + ", answers when asked: " + answered + (askingWorks ? "" : " (asking does not work here: " + askProblem + ")")
        + (problem.isEmpty() ? "" : "\nProblem: " + problem);
  }
}
