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
import android.content.SharedPreferences;
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
 * <p>The case is not in that number. Earbuds broadcast it themselves, together with each earbud
 * by itself, when their case is opened or closed with both of them inside: see
 * {@link BudsReading}, and {@link BudsPicker} for how the user's own are told from others'. The
 * case's level from that is kept for good and shown on the watch all the time, after the
 * earbuds' number: it is the level from when the case was last opened or closed, which is also
 * when it last changed. Each earbud by itself is only used to check a reading against the
 * phone's number: it is known for the moment the case was opened and not after, so the watch
 * shows the one number that follows the earbuds as they run down.
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

  // What the earbuds broadcast themselves
  private final BudsBroadcast radio;
  private final BudsPicker picker = new BudsPicker();
  private final SharedPreferences store;
  /** Each earbud as last broadcast, for the headset that is connected now. -1 where not known. */
  private int left = -1;
  private int right = -1;
  /** The case: its level, and when that was heard by the phone's clock. Kept for good. -1 if never. */
  private int box = -1;
  private long boxAt;
  private long boxSavedAt;
  /**
   * A reading was taken on judgement before the phone had a number for the pair to hold against
   * it. Until that number arrives, what the case said before is kept to go back to.
   */
  private boolean unproven;
  private long unprovenUntil;
  private int boxBefore = -1;
  private long boxAtBefore;
  private BudsReading lastTaken;
  private long lastTakenAt;
  private int sentBox = -2;
  private int goneBack;
  /** How often the phone's number for the pair has changed: shows that it follows the earbuds. */
  private int pairChanges;

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
    this.store = app.getSharedPreferences("earbuds", Context.MODE_PRIVATE);
    this.box = store.getInt("case", -1);
    this.boxAt = store.getLong("caseAt", 0);
    this.radio = new BudsBroadcast(app, main, log, this::onBroadcast);
  }

  /** Starts following the phone's headsets. Safe to call any number of times. */
  void start() {
    radio.start();
    if (sentBox == -2) {
      // The case's level from last time, for a watch that has just linked
      update();
    }
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
    radio.stop();
    if (!headsets.isEmpty()) {
      headsets.clear();
      left = -1;
      right = -1;
      log.add("earbuds", "Bluetooth is off: no headset connected");
      update();
    }
  }

  // ---------------------------------------------------------------- the earbuds' own broadcast

  /** The phone's one number for the pair, or -1. */
  private int pair() {
    int level = level();
    return level == UNKNOWN ? -1 : level;
  }

  private void onBroadcast(String address, int strength, BudsReading reading) {
    long now = SystemClock.elapsedRealtime();
    int certain = picker.certain;
    if (picker.heard(now, address, strength, reading, headsets.containsKey(address), pair())) {
      take(reading, picker.certain != certain);
    }
  }

  /** A reading that is the user's earbuds'. */
  private void take(BudsReading reading, boolean certain) {
    long now = SystemClock.elapsedRealtime();
    if (certain) {
      unproven = false;
    } else if (!unproven && !headsets.isEmpty() && pair() < 0 && (reading.left >= 0 || reading.right >= 0)) {
      unproven = true;
      unprovenUntil = now + 60_000;
      boxBefore = box;
      boxAtBefore = boxAt;
    }
    if (!reading.sameLevels(lastTaken)) {
      log.add("earbuds", "their own broadcast: " + reading.describe()
          + (certain ? " (from the headset's own address)" : ""));
    }
    lastTaken = reading;
    lastTakenAt = now;
    if (reading.box >= 0) {
      boolean other = reading.box != box;
      box = reading.box;
      boxAt = System.currentTimeMillis();
      if (other || boxAt - boxSavedAt > 60_000) {
        save();
      }
    }
    if (!headsets.isEmpty()) {
      left = reading.left;
      right = reading.right;
    }
    update();
  }

  /**
   * The phone's number for the pair has arrived. If a reading was taken before it and the two
   * cannot both be true, the reading was somebody else's earbuds: back to what was known before.
   */
  private void prove(int pairLevel) {
    if (!unproven) {
      return;
    }
    unproven = false;
    if (SystemClock.elapsedRealtime() > unprovenUntil || BudsReading.fits(pairLevel, left, right)) {
      return;
    }
    goneBack++;
    log.add("earbuds", "the broadcast that was taken does not match the phone's number for the pair ("
        + pairLevel + " %): it was other earbuds. Going back to what was known before.");
    box = boxBefore;
    boxAt = boxAtBefore;
    left = -1;
    right = -1;
    lastTaken = null;
    picker.letGo();
    save();
  }

  private void save() {
    boxSavedAt = boxAt;
    store.edit().putInt("case", box).putLong("caseAt", boxAt).apply();
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
      // Its case was opened a moment ago, and what it broadcast then was heard before this
      BudsReading opened = picker.moment(SystemClock.elapsedRealtime(), true, -1);
      if (opened != null) {
        take(opened, false);
      }
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
      int pairBefore = pair();
      headsets.remove(device.getAddress());
      log.add("earbuds", "a headset disconnected (" + headsets.size() + " connected now)");
      if (headsets.isEmpty()) {
        left = -1;
        right = -1;
        unproven = false;
      }
      // Its case was closed a moment ago: what it broadcast then says how full the case is now
      BudsReading closed = picker.moment(SystemClock.elapsedRealtime(), !headsets.isEmpty(), pairBefore);
      if (closed != null) {
        take(closed, false);
      }
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
      pairChanges++;
      log.add("earbuds", "battery " + how + ": " + level + " %");
    }
    headset.level = level;
    headset.heardAt = SystemClock.elapsedRealtime();
    prove(level);
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
    if (box != sentBox) {
      sentBox = box;
      watch.setEarbudsCase(box);
    }
    changed.run();
  }

  private static String ago(long ms) {
    long minutes = ms / 60_000;
    if (minutes < 1) {
      return "just now";
    }
    if (minutes < 60) {
      return minutes + " min ago";
    }
    if (minutes < 48 * 60) {
      return minutes / 60 + " h ago";
    }
    return minutes / (24 * 60) + " days ago";
  }

  private String caseLine() {
    if (box < 0) {
      return "\nCase: not heard from yet. It says how full it is when it is opened or closed next to the phone "
          + "with both earbuds inside.";
    }
    return "\nCase: " + box + " %, heard " + ago(Math.max(0, System.currentTimeMillis() - boxAt))
        + ". The watch keeps showing this.";
  }

  /** A few lines for the screen. The name is shown here and nowhere else. */
  String describe() {
    if (!problem.isEmpty()) {
      return "Earbuds: " + problem + "…";
    }
    String listening = radio.problem().isEmpty() ? "" : "\nNot listening for the case: " + radio.problem() + "…";
    if (headsets.isEmpty()) {
      return "Earbuds: none connected." + caseLine() + listening;
    }
    Headset headset = shown();
    if (headset == null) {
      Headset any = headsets.values().iterator().next();
      return "Earbuds: " + (any.name.isEmpty() ? "connected" : any.name) + ", but they have not shared a battery "
          + "level with the phone… Some only do a moment after connecting, some never." + caseLine() + listening;
    }
    return "✓ Earbuds: " + (headset.name.isEmpty() ? "" : headset.name + ", ") + headset.level
        + " %. They tell the phone each time this changes, and the watch follows." + caseLine() + listening;
  }

  /** For the report. No names and no addresses. */
  String dump() {
    Headset headset = shown();
    return "Headsets connected: " + headsets.size() + ", battery shown on the watch: "
        + (headset == null ? "none" : headset.level + " %") + "\nBattery announcements from Android: " + announced
        + ", answers when asked: " + answered + (askingWorks ? "" : " (asking does not work here: " + askProblem + ")")
        + (problem.isEmpty() ? "" : "\nProblem: " + problem)
        + "\n" + radio.dump() + "\n" + picker.dump()
        + "\nLast broadcast taken as yours: " + (lastTaken == null ? "none"
            : lastTaken.describe() + ", " + ago(SystemClock.elapsedRealtime() - lastTakenAt))
        + (goneBack == 0 ? "" : "\nTaken and then given up as somebody else's: " + goneBack)
        + "\nOn the watch now: earbuds " + shownPart(sent == UNKNOWN ? -1 : sent) + ", case " + shownPart(sentBox)
        + (box < 0 ? "" : " (heard " + ago(Math.max(0, System.currentTimeMillis() - boxAt)) + ")")
        + "\nNumbers for the pair that came in since the app started: " + pairChanges;
  }

  private static String shownPart(int level) {
    return level < 0 ? "none" : level + " %";
  }
}
