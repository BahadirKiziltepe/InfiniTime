package io.github.bahadirkiziltepe.watchhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Bluetooth link to the watch's music service: sends the song and play state, and hears the
 * watch's button presses. Everything here runs on the main thread.
 *
 * <p>Android lets several apps share one Bluetooth connection to the same device, so this works
 * next to Gadgetbridge as well as alone.
 *
 * <p>The watch's address is kept only in this app's private storage on the phone. It is never
 * written to the log or the report.
 *
 * <p>This link deliberately never asks the watch for a bigger message size. A watch update shares
 * this connection, and the watch's update code only copes with 20-byte pieces, which is what the
 * default size guarantees. Longer song titles still arrive whole: Android splits them up and the
 * watch puts them back together.
 */
@SuppressLint("MissingPermission") // Every entry point checks the permission, and calls are guarded.
final class WatchLink {
  interface Listener {
    void onWatchChanged();

    void onWatchEvent(int code);

    /** Messages about notifications or calls were lost on the way. What the watch shows may be incomplete. */
    void onPhoneMessagesLost();
  }

  // The watch firmware's music service (see src/components/ble/MusicService.cpp in this repo).
  private static final UUID MUSIC_SERVICE = musicUuid(0x00);
  private static final UUID CH_EVENT = musicUuid(0x01);
  private static final UUID CH_STATUS = musicUuid(0x02);
  private static final UUID CH_ARTIST = musicUuid(0x03);
  private static final UUID CH_TRACK = musicUuid(0x04);
  private static final UUID CH_ALBUM = musicUuid(0x05);
  private static final UUID CH_POSITION = musicUuid(0x06);
  private static final UUID CH_LENGTH = musicUuid(0x07);
  /** Not in stock InfiniTime: three bytes telling the watch what shuffle, repeat and save show. */
  private static final UUID CH_BUTTONS = musicUuid(0x0d);
  /** Notifications and calls (watch build 6 and newer). Takes the messages PhoneFrames builds. */
  private static final UUID CH_PHONE = musicUuid(0x0e);
  // The standard Bluetooth clock service, which the watch uses to have its time set.
  private static final UUID TIME_SERVICE = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb");
  private static final UUID CH_TIME = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb");
  private static final UUID CH_ZONE = UUID.fromString("00002a0f-0000-1000-8000-00805f9b34fb");
  private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  // What the watch announces while it is not connected: its name, plus these two services.
  private static final ParcelUuid ANNOUNCED_UPDATE_SERVICE =
      ParcelUuid.fromString("00001530-1212-efde-1523-785feabcd123");
  private static final ParcelUuid ANNOUNCED_HEART_RATE_SERVICE =
      ParcelUuid.fromString("0000180d-0000-1000-8000-00805f9b34fb");

  private static final UUID[] MUSIC =
      {CH_EVENT, CH_STATUS, CH_ARTIST, CH_TRACK, CH_ALBUM, CH_POSITION, CH_LENGTH, CH_BUTTONS, CH_PHONE};
  private static final UUID[] CLOCK = {CH_TIME, CH_ZONE};
  /**
   * Order in which changes are sent. The clock goes first; position last so it is as fresh as
   * possible. The album is left out: the watch has a slot for it but its screen does not show it.
   */
  private static final UUID[] SEND_ORDER =
      {CH_TIME, CH_ZONE, CH_TRACK, CH_ARTIST, CH_LENGTH, CH_STATUS, CH_BUTTONS, CH_POSITION};

  private static final String PREFS = "watch";
  private static final String KEY_ADDRESS = "address";
  private static final String KEY_ADDRESS_TYPE = "addressType";
  private static final String WATCH_NAME = "InfiniTime";

  private static final long SCAN_MS = 15_000;
  private static final long DIRECT_CONNECT_MS = 20_000;
  private static final long RECONNECT_DELAY_MS = 2_000;
  private static final long RETRY_DELAY_MS = 30_000;
  private static final long DISCOVER_DELAY_MS = 400;
  private static final long STEP_TIMEOUT_MS = 5_000;
  private static final long WRITE_TIMEOUT_MS = 5_000;
  private static final long BUSY_RETRY_MS = 300;
  private static final int MAX_BUSY_RETRIES = 10;
  private static final int DEFAULT_MTU = 23;
  private static final long POSITION_DRIFT_MS = 1_500;
  /** The watch keeps good time by itself; this only corrects slow drift. */
  private static final long CLOCK_RESYNC_MS = 6 * 60 * 60 * 1000L;

  private static final int STAGE_IDLE = 0;
  private static final int STAGE_NOTIFY = 1;
  private static final int STAGE_DONE = 2;

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final Listener listener;
  private final BluetoothManager manager;
  private final SharedPreferences prefs;

  // Link state.
  private BluetoothGatt gatt;
  private boolean backgroundLink;
  private boolean connected;
  private boolean ready;
  private boolean scanning;
  private boolean probing;
  private int setupStage = STAGE_IDLE;
  private int mtu = DEFAULT_MTU;
  private boolean notifyOn;
  private boolean musicServiceFound;
  private boolean announcedFirstSend;
  private String phase = "No watch chosen yet. Tap Find watch.";
  private final Map<UUID, BluetoothGattCharacteristic> chars = new HashMap<>();
  private final ArrayDeque<BluetoothDevice> probeQueue = new ArrayDeque<>();

  // What the watch should show, what it has been sent, and what it refused.
  private final Map<UUID, byte[]> desired = new HashMap<>();
  private final Map<UUID, byte[]> sent = new HashMap<>();
  private final Map<UUID, byte[]> failed = new HashMap<>();
  private UUID inFlight;
  private byte[] inFlightValue;
  private int busyRetries;

  // Notifications and calls are a string of messages that have to arrive in order, not a value
  // that can simply be replaced by a newer one.
  private static final int MAX_PHONE_QUEUE = 600;
  private static final int MAX_PHONE_FAILURES = 3;
  private final ArrayDeque<byte[]> phoneQueue = new ArrayDeque<>();
  private int phoneFailures;
  private int phoneSent;

  // Now playing, as last reported by the phone.
  private String npTitle = "";
  private String npArtist = "";
  private String npAlbum = "";
  private boolean npPlaying;
  private long npPositionMs = -1;
  private long npPositionAt;
  private float npSpeed = 1f;
  private long npDurationMs;

  // The position last sent, to tell when the watch's own clock has drifted from the phone's.
  private boolean positionDirty = true;
  private long sentPositionMs = -1;
  private long sentPositionAt;
  private boolean sentPlaying;
  private float sentSpeed = 1f;

  // The watch's clock.
  private boolean timeDirty = true;
  private boolean zoneDirty = true;
  private boolean clockSet;
  private long clockSetAt;
  private int clockOffsetSeconds;

  /** While a watch update is being sent, nothing else is written to the watch. */
  private boolean paused;

  /** Set if the watch would not take a title longer than one message. Titles are then cut shorter. */
  private boolean shortTextOnly;

  // Counters for the report.
  private int links;
  private int writesOk;
  private int writesFailed;
  private int eventsHeard;
  private String lastEvent = "";
  private String lastError = "";

  private final Runnable startLater = this::start;
  private final Runnable pumpLater = this::pump;
  private final Runnable discoverLater = this::discover;
  private final Runnable stepTimeout = this::onStepTimeout;
  private final Runnable writeTimeout = this::onWriteTimeout;
  private final Runnable directTimeout = this::onDirectTimeout;
  private final Runnable scanTimeout = this::onScanTimeout;

  WatchLink(Context app, Handler main, EventLog log, Listener listener) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.listener = listener;
    this.manager = app.getSystemService(BluetoothManager.class);
    this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static UUID musicUuid(int n) {
    return UUID.fromString(String.format(Locale.ROOT, "000000%02x-78fc-48fe-8e23-433b3a1942d0", n));
  }

  // ---------------------------------------------------------------- permissions and adapter

  boolean hasConnectPermission() {
    return app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
  }

  boolean hasScanPermission() {
    return app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
  }

  private BluetoothAdapter adapter() {
    return manager == null ? null : manager.getAdapter();
  }

  private boolean bluetoothOn() {
    try {
      BluetoothAdapter adapter = adapter();
      return adapter != null && adapter.isEnabled();
    } catch (RuntimeException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------- connecting

  /** Links to the remembered watch whenever it is in range. Safe to call any number of times. */
  void start() {
    main.removeCallbacks(startLater);
    if (gatt != null || scanning || probing) {
      return;
    }
    if (!hasConnectPermission()) {
      setPhase("Waiting for step 2.");
      return;
    }
    if (!bluetoothOn()) {
      setPhase("✗ Bluetooth is off.");
      return;
    }
    String address = prefs.getString(KEY_ADDRESS, null);
    if (address == null) {
      setPhase("No watch chosen yet. Tap Find watch.");
      return;
    }
    BluetoothDevice device = deviceFor(address, prefs.getInt(KEY_ADDRESS_TYPE, -1));
    if (device == null) {
      setPhase("✗ Could not look up the watch. Tap Find watch.");
      return;
    }
    open(device, true);
  }

  /** Looks for the watch: first among devices this phone is already connected to, then nearby. */
  void findWatch() {
    if (!hasConnectPermission() || !hasScanPermission()) {
      setPhase("Waiting for step 2.");
      return;
    }
    if (!bluetoothOn()) {
      setPhase("✗ Bluetooth is off. Turn it on and tap Find watch again.");
      return;
    }
    if (scanning) {
      return;
    }
    main.removeCallbacks(startLater);
    probing = false;
    probeQueue.clear();

    List<BluetoothDevice> linked = connectedDevices();
    for (BluetoothDevice device : linked) {
      if (isWatchName(nameOf(device))) {
        log.add("watch", "found the watch among devices already connected to this phone");
        remember(device);
        open(device, false);
        return;
      }
    }
    log.add("watch", "not among the " + linked.size() + " connected device(s); searching nearby");
    startScan();
  }

  void onBluetoothOff() {
    stopScan();
    probing = false;
    probeQueue.clear();
    closeGatt();
    setPhase("✗ Bluetooth is off.");
  }

  void onBluetoothOn() {
    scheduleStart(RECONNECT_DELAY_MS);
  }

  private void scheduleStart(long delayMs) {
    main.removeCallbacks(startLater);
    main.postDelayed(startLater, delayMs);
  }

  private void open(BluetoothDevice device, boolean background) {
    closeGatt();
    backgroundLink = background;
    setPhase(background ? "Waiting for the watch to come into range…" : "Connecting to the watch…");
    try {
      gatt = device.connectGatt(app, background, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK,
          main);
    } catch (RuntimeException e) {
      gatt = null;
      problem("could not start the link (" + e.getClass().getSimpleName() + ")");
    }
    if (gatt == null) {
      if (probing) {
        probeNext();
      } else {
        setPhase("✗ Could not start the link. Trying again shortly.");
        scheduleStart(RETRY_DELAY_MS);
      }
      return;
    }
    if (!background) {
      main.postDelayed(directTimeout, DIRECT_CONNECT_MS);
    }
  }

  private void closeGatt() {
    main.removeCallbacks(directTimeout);
    main.removeCallbacks(stepTimeout);
    main.removeCallbacks(writeTimeout);
    main.removeCallbacks(pumpLater);
    main.removeCallbacks(discoverLater);
    BluetoothGatt old = gatt;
    gatt = null;
    connected = false;
    ready = false;
    setupStage = STAGE_IDLE;
    mtu = DEFAULT_MTU;
    notifyOn = false;
    musicServiceFound = false;
    announcedFirstSend = false;
    inFlight = null;
    inFlightValue = null;
    busyRetries = 0;
    chars.clear();
    phoneQueue.clear();
    phoneFailures = 0;
    // A fresh link starts from nothing: the watch may have restarted and forgotten everything.
    sent.clear();
    failed.clear();
    sentPositionMs = -1;
    positionDirty = true;
    timeDirty = true;
    zoneDirty = true;
    clockSet = false;
    if (old != null) {
      try {
        old.disconnect();
      } catch (RuntimeException ignored) {
        // Already gone.
      }
      try {
        old.close();
      } catch (RuntimeException ignored) {
        // Already gone.
      }
    }
  }

  private void onDirectTimeout() {
    if (gatt == null || connected) {
      return;
    }
    log.add("watch", "no answer from the device");
    closeGatt();
    if (probing) {
      probeNext();
    } else {
      start();
    }
  }

  private BluetoothDevice deviceFor(String address, int type) {
    BluetoothAdapter adapter = adapter();
    if (adapter == null) {
      return null;
    }
    try {
      int useType = type;
      if (useType != BluetoothDevice.ADDRESS_TYPE_PUBLIC && useType != BluetoothDevice.ADDRESS_TYPE_RANDOM) {
        // Not recorded. Addresses the chip made up for itself have their two top bits set.
        int first = Integer.parseInt(address.substring(0, 2), 16);
        useType = (first & 0xc0) == 0xc0 ? BluetoothDevice.ADDRESS_TYPE_RANDOM : BluetoothDevice.ADDRESS_TYPE_PUBLIC;
      }
      return adapter.getRemoteLeDevice(address, useType);
    } catch (RuntimeException e) {
      try {
        return adapter.getRemoteDevice(address);
      } catch (RuntimeException e2) {
        return null;
      }
    }
  }

  private void remember(BluetoothDevice device) {
    prefs.edit().putString(KEY_ADDRESS, device.getAddress()).putInt(KEY_ADDRESS_TYPE, addressTypeOf(device)).apply();
  }

  /** The kind of address the device uses, or -1. Read by name so it also builds against older SDKs. */
  private static int addressTypeOf(BluetoothDevice device) {
    try {
      Object type = BluetoothDevice.class.getMethod("getAddressType").invoke(device);
      return type instanceof Integer ? (Integer) type : -1;
    } catch (Exception e) {
      return -1;
    }
  }

  private List<BluetoothDevice> connectedDevices() {
    try {
      List<BluetoothDevice> devices = manager.getConnectedDevices(BluetoothProfile.GATT);
      return devices == null ? new ArrayList<>() : devices;
    } catch (RuntimeException e) {
      return new ArrayList<>();
    }
  }

  private static String nameOf(BluetoothDevice device) {
    try {
      return device.getName();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static boolean isWatchName(String name) {
    return name != null && name.trim().equalsIgnoreCase(WATCH_NAME);
  }

  // ---------------------------------------------------------------- searching

  private void startScan() {
    BluetoothAdapter adapter = adapter();
    BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
    if (scanner == null) {
      setPhase("✗ Bluetooth is off.");
      return;
    }
    closeGatt();
    try {
      ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
      scanner.startScan(null, settings, scanCallback);
    } catch (RuntimeException e) {
      problem("could not search (" + e.getClass().getSimpleName() + ")");
      setPhase("✗ Could not search for the watch.");
      return;
    }
    scanning = true;
    setPhase("Searching for the watch…");
    main.postDelayed(scanTimeout, SCAN_MS);
  }

  private void stopScan() {
    main.removeCallbacks(scanTimeout);
    if (!scanning) {
      return;
    }
    scanning = false;
    try {
      BluetoothAdapter adapter = adapter();
      BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
      if (scanner != null) {
        scanner.stopScan(scanCallback);
      }
    } catch (RuntimeException ignored) {
      // Bluetooth went away mid-search.
    }
  }

  private final ScanCallback scanCallback = new ScanCallback() {
    @Override
    public void onScanResult(int callbackType, ScanResult result) {
      if (!scanning || result == null) {
        return;
      }
      ScanRecord record = result.getScanRecord();
      String name = record == null ? null : record.getDeviceName();
      if (name == null) {
        name = nameOf(result.getDevice());
      }
      boolean named = isWatchName(name);
      if (!named) {
        // The name travels in a second, optional packet. Without it, go by the announced services.
        List<ParcelUuid> services = record == null ? null : record.getServiceUuids();
        if (name != null || services == null || !services.contains(ANNOUNCED_UPDATE_SERVICE)
            || !services.contains(ANNOUNCED_HEART_RATE_SERVICE)) {
          return;
        }
      }
      stopScan();
      log.add("watch", named ? "found the watch nearby" : "found a device nearby that announces itself like the watch");
      remember(result.getDevice());
      open(result.getDevice(), false);
    }

    @Override
    public void onScanFailed(int errorCode) {
      scanning = false;
      main.removeCallbacks(scanTimeout);
      problem("search failed (code " + errorCode + ")");
      setPhase("✗ Search failed. Turn Bluetooth off and on, then tap Find watch again.");
    }
  };

  private void onScanTimeout() {
    stopScan();
    // The watch only announces itself while nothing is connected to it. If another app holds it
    // and the phone did not report its name, it is one of the unnamed connected devices.
    probeQueue.clear();
    for (BluetoothDevice device : connectedDevices()) {
      String name = nameOf(device);
      if (name == null || name.trim().isEmpty()) {
        probeQueue.add(device);
      }
    }
    if (!probeQueue.isEmpty()) {
      log.add("watch", "not seen nearby; checking " + probeQueue.size() + " unnamed connected device(s)");
      probing = true;
      probeNext();
      return;
    }
    notFound();
  }

  private void probeNext() {
    BluetoothDevice next = probeQueue.poll();
    if (next == null) {
      probing = false;
      notFound();
      return;
    }
    probing = true;
    open(next, false);
    // open() moves on by itself if this device cannot be opened, so only label a check in progress.
    if (gatt != null && probing) {
      setPhase("Checking a connected device…");
    }
  }

  private void notFound() {
    log.add("watch", "watch not found");
    if (prefs.getString(KEY_ADDRESS, null) != null) {
      start();
      setPhase("✗ Watch not seen right now. Keep it close; it links by itself when it shows up.");
    } else {
      setPhase("✗ Watch not found. Keep it close with Bluetooth on, then tap Find watch again.");
    }
  }

  // ---------------------------------------------------------------- link events

  private final BluetoothGattCallback callback = new BluetoothGattCallback() {
    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
      if (g != gatt) {
        return;
      }
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        main.removeCallbacks(directTimeout);
        connected = true;
        links++;
        if (!probing) {
          log.add("watch", "linked (link " + links + ")");
          setPhase("Linked. Looking for the music channel…");
        }
        main.removeCallbacks(discoverLater);
        main.postDelayed(discoverLater, DISCOVER_DELAY_MS);
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        boolean wasProbing = probing;
        if (!wasProbing) {
          log.add("watch", "link dropped (code " + status + ")");
        }
        closeGatt();
        if (wasProbing) {
          probeNext();
        } else {
          setPhase("Watch out of reach. It links again by itself.");
          scheduleStart(RECONNECT_DELAY_MS);
        }
      }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
      if (g != gatt) {
        return;
      }
      BluetoothGattService service = status == BluetoothGatt.GATT_SUCCESS ? g.getService(MUSIC_SERVICE) : null;
      if (service == null) {
        if (probing) {
          closeGatt();
          probeNext();
          return;
        }
        problem("this device has no music channel (code " + status + ")");
        setPhase("✗ Linked, but found no music channel on it.");
        return;
      }
      musicServiceFound = true;
      if (probing) {
        probing = false;
        probeQueue.clear();
        remember(g.getDevice());
        log.add("watch", "found the watch among the connected devices");
      }
      chars.clear();
      for (UUID id : MUSIC) {
        BluetoothGattCharacteristic ch = service.getCharacteristic(id);
        if (ch != null) {
          chars.put(id, ch);
        }
      }
      BluetoothGattService clock = g.getService(TIME_SERVICE);
      if (clock != null) {
        for (UUID id : CLOCK) {
          BluetoothGattCharacteristic ch = clock.getCharacteristic(id);
          if (ch != null) {
            chars.put(id, ch);
          }
        }
      }
      if (!chars.containsKey(CH_TIME)) {
        problem("this watch has no clock channel; its time cannot be set from the phone");
      }

      BluetoothGattCharacteristic events = chars.get(CH_EVENT);
      if (events == null) {
        problem("the music channel has no button messages");
      } else {
        boolean local = g.setCharacteristicNotification(events, true);
        BluetoothGattDescriptor cccd = events.getDescriptor(CCCD);
        if (!local || cccd == null) {
          problem("could not listen for button messages");
        } else {
          int rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
          if (rc == BluetoothStatusCodes.SUCCESS) {
            setupStage = STAGE_NOTIFY;
            main.postDelayed(stepTimeout, STEP_TIMEOUT_MS);
            return;
          }
          problem("could not switch on button messages (code " + rc + ")");
        }
      }
      finishSetup();
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
      if (g != gatt || setupStage != STAGE_NOTIFY) {
        return;
      }
      main.removeCallbacks(stepTimeout);
      notifyOn = status == BluetoothGatt.GATT_SUCCESS;
      if (!notifyOn) {
        problem("the watch refused to switch on button messages (code " + status + ")");
      }
      finishSetup();
    }

    @Override
    public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
      // This link never asks for a different size, but another app on the same connection may.
      if (g == gatt && status == BluetoothGatt.GATT_SUCCESS && newMtu >= DEFAULT_MTU && newMtu != mtu) {
        mtu = newMtu;
        log.add("watch", "message size is now " + mtu + " (changed by another app)");
      }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
      if (g != gatt || inFlight == null || !inFlight.equals(ch.getUuid())) {
        return;
      }
      main.removeCallbacks(writeTimeout);
      UUID id = inFlight;
      byte[] value = inFlightValue;
      inFlight = null;
      inFlightValue = null;
      if (id.equals(CH_PHONE)) {
        onPhoneMessageDone(value, status == BluetoothGatt.GATT_SUCCESS, "code " + status);
        listener.onWatchChanged();
        pump();
        return;
      }
      if (status == BluetoothGatt.GATT_SUCCESS) {
        writesOk++;
        if (!isLive(id)) {
          sent.put(id, value);
          failed.remove(id);
        }
        if (id.equals(CH_TIME) && !clockSet) {
          clockSet = true;
          log.add("watch", "set the watch's clock");
        }
        if (!announcedFirstSend) {
          announcedFirstSend = true;
          log.add("watch", "the watch accepted what was sent");
        }
      } else {
        writesFailed++;
        problem("the watch refused \"" + nameOf(id) + "\" (code " + status + ")");
        if (!isLive(id)) {
          failed.put(id, value);
          fallBackToShortText(id, value);
        }
      }
      listener.onWatchChanged();
      pump();
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
      if (g != gatt || !CH_EVENT.equals(ch.getUuid()) || value == null || value.length == 0) {
        return;
      }
      eventsHeard++;
      int code = value[0] & 0xff;
      lastEvent = Core.eventName(code);
      listener.onWatchEvent(code);
    }

    @Override
    public void onServiceChanged(BluetoothGatt g) {
      if (g != gatt) {
        return;
      }
      log.add("watch", "the watch changed its channels; looking again");
      main.removeCallbacks(stepTimeout);
      main.removeCallbacks(writeTimeout);
      ready = false;
      setupStage = STAGE_IDLE;
      inFlight = null;
      inFlightValue = null;
      chars.clear();
      main.removeCallbacks(discoverLater);
      main.postDelayed(discoverLater, DISCOVER_DELAY_MS);
    }
  };

  private void discover() {
    BluetoothGatt g = gatt;
    if (g == null || !connected) {
      return;
    }
    boolean started = false;
    try {
      started = g.discoverServices();
    } catch (RuntimeException ignored) {
      // Reported below.
    }
    if (!started) {
      if (probing) {
        closeGatt();
        probeNext();
        return;
      }
      problem("could not look for the music channel");
    }
  }

  private void onStepTimeout() {
    if (gatt != null && setupStage == STAGE_NOTIFY) {
      problem("no answer when switching on button messages; carrying on");
      finishSetup();
    }
  }

  private void finishSetup() {
    if (ready || gatt == null) {
      return;
    }
    setupStage = STAGE_DONE;
    ready = true;
    log.add("watch", "ready: button messages " + (notifyOn ? "on" : "not confirmed") + ", message size " + mtu
        + ", channels " + chars.size() + " of " + (MUSIC.length + CLOCK.length));
    setPhase("✓ Linked to the watch.");
    rebuildDesired();
    pump();
  }

  // ---------------------------------------------------------------- sending

  /**
   * Tells the link what the watch should show. {@code positionMs} is where the song is right now,
   * or -1 if unknown.
   */
  void setNowPlaying(String title, String artist, String album, boolean playing, long positionMs, float speed,
      long durationMs) {
    npTitle = title == null ? "" : title;
    npArtist = artist == null ? "" : artist;
    npAlbum = album == null ? "" : album;
    npPlaying = playing;
    npPositionMs = positionMs;
    npPositionAt = SystemClock.elapsedRealtime();
    npSpeed = speed > 0 ? speed : 1f;
    npDurationMs = Math.max(0, durationMs);
    rebuildDesired();
    pump();
  }

  /**
   * Tells the watch what the player's shuffle, repeat and save buttons show (ButtonStates values).
   * Watch builds without the second row of buttons do not have this channel; it is then skipped.
   */
  void setButtonStates(int shuffle, int repeat, int saved) {
    desired.put(CH_BUTTONS, new byte[] {(byte) shuffle, (byte) repeat, (byte) saved});
    pump();
  }

  private long positionNowMs() {
    if (npPositionMs < 0) {
      return -1;
    }
    if (!npPlaying) {
      return npPositionMs;
    }
    return npPositionMs + (long) ((SystemClock.elapsedRealtime() - npPositionAt) * npSpeed);
  }

  private void rebuildDesired() {
    int limit = shortTextOnly ? Math.max(1, Math.min(WatchText.MAX_BYTES, mtu - 3)) : WatchText.MAX_BYTES;
    desired.put(CH_TRACK, WatchText.encode(npTitle, limit));
    desired.put(CH_ARTIST, WatchText.encode(npArtist, limit));
    desired.put(CH_LENGTH, WatchText.int32(npDurationMs / 1000));
    desired.put(CH_STATUS, new byte[] {(byte) (npPlaying ? 1 : 0)});

    // The watch advances the position by itself while a song plays, so it only needs a new one
    // when its idea of the position no longer matches the phone's (a seek, a new song, a pause).
    long actual = positionNowMs();
    if (actual >= 0) {
      if (sentPositionMs < 0 || sentPlaying != npPlaying) {
        positionDirty = true;
      } else {
        long expected = sentPositionMs;
        if (sentPlaying) {
          expected += (long) ((SystemClock.elapsedRealtime() - sentPositionAt) * sentSpeed);
        }
        if (Math.abs(actual - expected) > POSITION_DRIFT_MS) {
          positionDirty = true;
        }
      }
    }
  }

  /**
   * Queues messages about notifications or calls (see PhoneFrames). They are sent in order, ahead
   * of song details. Returns false if the watch is not linked or its build has no channel for them.
   */
  boolean sendPhone(List<byte[]> messages) {
    if (!hasPhoneChannel()) {
      return false;
    }
    if (phoneQueue.size() + messages.size() > MAX_PHONE_QUEUE) {
      problem("too many notifications waiting to be sent; starting over");
      phoneQueue.clear();
      listener.onPhoneMessagesLost();
      return false;
    }
    phoneQueue.addAll(messages);
    pump();
    return true;
  }

  /** True when a watch is linked and its build can show notifications and calls. */
  boolean hasPhoneChannel() {
    return ready && chars.containsKey(CH_PHONE);
  }

  private void onPhoneMessageDone(byte[] message, boolean ok, String detail) {
    if (ok) {
      writesOk++;
      phoneSent++;
      phoneFailures = 0;
      if (phoneQueue.peek() == message) {
        phoneQueue.poll();
      }
      return;
    }
    // One missing piece makes whatever it belonged to wrong, so drop what is waiting and let the
    // sender start over from a clean slate.
    writesFailed++;
    phoneFailures++;
    phoneQueue.clear();
    problem("the watch did not take a notification or call message (" + detail + ")");
    if (phoneFailures <= MAX_PHONE_FAILURES) {
      listener.onPhoneMessagesLost();
    }
  }

  /** Values worked out at the moment of sending (clock, position) rather than remembered. */
  private static boolean isLive(UUID id) {
    return id.equals(CH_POSITION) || id.equals(CH_TIME) || id.equals(CH_ZONE) || id.equals(CH_PHONE);
  }

  /** Sends the next thing the watch does not have yet. One message at a time, as Bluetooth requires. */
  private void pump() {
    BluetoothGatt g = gatt;
    if (g == null || !ready || paused || inFlight != null) {
      return;
    }
    BluetoothGattCharacteristic phone = chars.get(CH_PHONE);
    if (phone != null && !phoneQueue.isEmpty()) {
      byte[] message = phoneQueue.peek();
      int rc;
      try {
        rc = g.writeCharacteristic(phone, message, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
      } catch (RuntimeException e) {
        rc = -1;
      }
      if (rc == BluetoothStatusCodes.SUCCESS) {
        inFlight = CH_PHONE;
        inFlightValue = message;
        busyRetries = 0;
        main.postDelayed(writeTimeout, WRITE_TIMEOUT_MS);
        return;
      }
      if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && busyRetries < MAX_BUSY_RETRIES) {
        busyRetries++;
        main.removeCallbacks(pumpLater);
        main.postDelayed(pumpLater, BUSY_RETRY_MS);
        return;
      }
      onPhoneMessageDone(message, false, "could not send, code " + rc);
    }
    for (UUID id : SEND_ORDER) {
      BluetoothGattCharacteristic ch = chars.get(id);
      if (ch == null) {
        continue;
      }
      ZonedDateTime now = null;
      long positionMs = -1;
      byte[] value;
      if (id.equals(CH_TIME)) {
        if (!timeDirty) {
          continue;
        }
        now = ZonedDateTime.now();
        value = WatchTime.currentTime(now);
      } else if (id.equals(CH_ZONE)) {
        if (!zoneDirty) {
          continue;
        }
        value = WatchTime.localTime(ZonedDateTime.now());
      } else if (id.equals(CH_POSITION)) {
        if (!positionDirty) {
          continue;
        }
        positionMs = positionNowMs();
        if (positionMs < 0) {
          positionDirty = false;
          continue;
        }
        value = WatchText.int32(positionMs / 1000);
      } else {
        value = desired.get(id);
        if (value == null || Arrays.equals(value, sent.get(id)) || Arrays.equals(value, failed.get(id))) {
          continue;
        }
      }

      int rc;
      try {
        rc = g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
      } catch (RuntimeException e) {
        rc = -1;
      }
      if (rc == BluetoothStatusCodes.SUCCESS) {
        inFlight = id;
        inFlightValue = value;
        busyRetries = 0;
        if (id.equals(CH_TIME)) {
          timeDirty = false;
          clockSetAt = SystemClock.elapsedRealtime();
          clockOffsetSeconds = WatchTime.offsetSeconds(now);
        } else if (id.equals(CH_ZONE)) {
          zoneDirty = false;
        } else if (id.equals(CH_POSITION)) {
          positionDirty = false;
          sentPositionMs = positionMs;
          sentPositionAt = SystemClock.elapsedRealtime();
          sentPlaying = npPlaying;
          sentSpeed = npSpeed;
        }
        main.postDelayed(writeTimeout, WRITE_TIMEOUT_MS);
        return;
      }
      if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && busyRetries < MAX_BUSY_RETRIES) {
        busyRetries++;
        main.removeCallbacks(pumpLater);
        main.postDelayed(pumpLater, BUSY_RETRY_MS);
        return;
      }
      writesFailed++;
      problem("could not send \"" + nameOf(id) + "\" (code " + rc + ")");
      if (id.equals(CH_TIME)) {
        timeDirty = false;
      } else if (id.equals(CH_ZONE)) {
        zoneDirty = false;
      } else if (id.equals(CH_POSITION)) {
        positionDirty = false;
      } else {
        failed.put(id, value);
      }
    }
  }

  private void onWriteTimeout() {
    if (inFlight == null) {
      return;
    }
    UUID id = inFlight;
    byte[] value = inFlightValue;
    inFlight = null;
    inFlightValue = null;
    if (id.equals(CH_PHONE)) {
      onPhoneMessageDone(value, false, "no answer");
      listener.onWatchChanged();
      pump();
      return;
    }
    writesFailed++;
    problem("no answer from the watch for \"" + nameOf(id) + "\"");
    if (!isLive(id) && value != null) {
      failed.put(id, value);
      fallBackToShortText(id, value);
    }
    listener.onWatchChanged();
    pump();
  }

  /**
   * A title longer than one message is split up by Android and put back together by the watch.
   * If the watch does not manage that, stop relying on it and send titles that fit one message.
   */
  private void fallBackToShortText(UUID id, byte[] value) {
    boolean text = id.equals(CH_TRACK) || id.equals(CH_ARTIST);
    if (text && !shortTextOnly && value.length > mtu - 3) {
      shortTextOnly = true;
      log.add("watch", "the watch did not take a long title; sending shorter ones from now on");
      rebuildDesired();
    }
  }

  // ---------------------------------------------------------------- clock and updates

  /** Sets the watch's clock again, for example after the phone's time or time zone changed. */
  void syncClock() {
    timeDirty = true;
    zoneDirty = true;
    pump();
  }

  /**
   * Called about once a minute while the phone is awake. Sets the watch's clock again when
   * daylight saving time has started or ended, and every few hours to correct drift.
   */
  void clockTick() {
    if (!ready || !clockSet) {
      return;
    }
    boolean shifted = WatchTime.offsetSeconds(ZonedDateTime.now()) != clockOffsetSeconds;
    boolean stale = SystemClock.elapsedRealtime() - clockSetAt > CLOCK_RESYNC_MS;
    if (shifted || stale) {
      syncClock();
    }
  }

  /** Stops (or resumes) everything this link sends, so a watch update has the connection to itself. */
  void setPaused(boolean pause) {
    paused = pause;
    if (!pause) {
      pump();
    }
  }

  /**
   * The watch's Bluetooth address for the updater, or null if no watch is linked. It must stay
   * inside the app: never put it in the log or the report.
   */
  String addressForUpdate() {
    return ready ? prefs.getString(KEY_ADDRESS, null) : null;
  }

  // ---------------------------------------------------------------- status text

  boolean isReady() {
    return ready;
  }

  /** The connection's current message size (23 unless another app on the same connection raised it). */
  int messageSize() {
    return mtu;
  }

  /** A few lines for the screen. */
  String describe() {
    StringBuilder sb = new StringBuilder(phase);
    if (ready) {
      sb.append("\nSent to the watch: ").append(writesOk);
      if (writesFailed > 0) {
        sb.append(" (").append(writesFailed).append(" failed)");
      }
    }
    return sb.toString();
  }

  String describeEvents() {
    if (eventsHeard == 0) {
      return "Nothing heard from the watch yet.";
    }
    return "Heard " + eventsHeard + " from the watch. Last: " + lastEvent + ".";
  }

  /** Everything known, for the report. Never includes the watch's address. */
  String dump() {
    StringBuilder sb = new StringBuilder();
    boolean chosen = prefs.getString(KEY_ADDRESS, null) != null;
    sb.append("Bluetooth permission: connect ").append(yesNo(hasConnectPermission())).append(", search ")
        .append(yesNo(hasScanPermission())).append('\n');
    sb.append("Bluetooth on: ").append(yesNo(bluetoothOn())).append('\n');
    sb.append("Watch remembered: ").append(yesNo(chosen)).append(" (address kind ")
        .append(addressKind(prefs.getInt(KEY_ADDRESS_TYPE, -1))).append(")\n");
    sb.append("Status: ").append(phase).append('\n');
    sb.append("Linked: ").append(yesNo(connected)).append(", ready: ").append(yesNo(ready)).append(", links so far: ")
        .append(links).append(", waiting in background: ").append(yesNo(gatt != null && backgroundLink && !connected))
        .append('\n');
    sb.append("Music channel: ").append(yesNo(musicServiceFound)).append(", button messages on: ")
        .append(yesNo(notifyOn)).append(", message size: ").append(mtu).append('\n');
    sb.append("Channels found:");
    for (UUID id : SEND_ORDER) {
      if (chars.containsKey(id)) {
        sb.append(' ').append(nameOf(id)).append(',');
      }
    }
    sb.append(chars.containsKey(CH_EVENT) ? " buttons" : " (no buttons)").append('\n');
    sb.append("Notifications and calls channel: ").append(yesNo(chars.containsKey(CH_PHONE))).append(", messages sent: ")
        .append(phoneSent).append(", waiting: ").append(phoneQueue.size()).append('\n');
    sb.append("Clock set on this link: ").append(yesNo(clockSet)).append(", paused for an update: ")
        .append(yesNo(paused)).append(", short titles only: ").append(yesNo(shortTextOnly)).append('\n');
    sb.append("Sent to the watch: ").append(writesOk).append(" ok, ").append(writesFailed).append(" failed\n");
    sb.append("Heard from the watch: ").append(eventsHeard).append(lastEvent.isEmpty() ? "" : ", last: " + lastEvent)
        .append('\n');
    sb.append("Last problem: ").append(lastError.isEmpty() ? "none" : lastError).append('\n');
    if (hasConnectPermission()) {
      List<BluetoothDevice> linked = connectedDevices();
      boolean among = false;
      for (BluetoothDevice device : linked) {
        among |= isWatchName(nameOf(device));
      }
      sb.append("Devices connected to this phone over Bluetooth LE: ").append(linked.size())
          .append(", one of them named like the watch: ").append(yesNo(among)).append('\n');
    }
    return sb.toString();
  }

  private void setPhase(String text) {
    phase = text;
    listener.onWatchChanged();
  }

  private void problem(String text) {
    lastError = text;
    log.add("watch", text);
  }

  private static String yesNo(boolean value) {
    return value ? "yes" : "no";
  }

  private static String addressKind(int type) {
    if (type == BluetoothDevice.ADDRESS_TYPE_PUBLIC) {
      return "public";
    }
    if (type == BluetoothDevice.ADDRESS_TYPE_RANDOM) {
      return "random";
    }
    return "not recorded";
  }

  private static String nameOf(UUID id) {
    if (CH_EVENT.equals(id)) {
      return "buttons";
    }
    if (CH_STATUS.equals(id)) {
      return "play state";
    }
    if (CH_ARTIST.equals(id)) {
      return "artist";
    }
    if (CH_TRACK.equals(id)) {
      return "title";
    }
    if (CH_ALBUM.equals(id)) {
      return "album";
    }
    if (CH_POSITION.equals(id)) {
      return "position";
    }
    if (CH_LENGTH.equals(id)) {
      return "length";
    }
    if (CH_BUTTONS.equals(id)) {
      return "button states";
    }
    if (CH_PHONE.equals(id)) {
      return "notifications and calls";
    }
    if (CH_TIME.equals(id)) {
      return "clock";
    }
    if (CH_ZONE.equals(id)) {
      return "time zone";
    }
    return "other";
  }
}
