package io.github.bahadirkiziltepe.watchhelper;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;

import java.util.Collections;
import java.util.List;

/**
 * Listens for what earbuds broadcast about their batteries: see {@link BudsReading}.
 *
 * <p>The broadcast only lasts some seconds, at the moment a case is opened or closed, and there is
 * no telling when that will be. So this listens all the time, with a filter that lets nothing but
 * Fast Pair broadcasts through and at a pace that is easy on the battery: Android listens about a
 * quarter of the time, which is enough to catch a broadcast that is repeated four times a second
 * for five seconds or more.
 *
 * <p>Android turns a listening that has gone on for half an hour into one that only hears what
 * other apps happen to ask for, so it is started afresh every twenty-five minutes. That is far
 * from the limit of five starts in thirty seconds, which the search for the watch needs to itself.
 *
 * <p>Everything here runs on the main thread.
 */
@SuppressLint("MissingPermission") // Without the Nearby devices permission Android throws, and that is caught.
final class BudsBroadcast {
  interface Listener {
    /** Earbuds broadcast their batteries. {@code strength} is how strongly it arrived, in dBm. */
    void onBudsBroadcast(String address, int strength, BudsReading reading);
  }

  /** The service all Fast Pair devices broadcast under. */
  private static final ParcelUuid FAST_PAIR = ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb");
  private static final long AFRESH_MS = 25 * 60_000L;
  private static final long RETRY_MS = 5 * 60_000L;

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final Listener listener;

  private boolean wanted;
  private boolean listening;
  private BluetoothLeScanner scanner;
  private String problem = "";

  // For the report
  private int starts;
  private long broadcasts;
  private long withBatteries;

  private final Runnable afresh = () -> {
    end();
    begin();
  };

  private final ScanCallback callback = new ScanCallback() {
    @Override
    public void onScanResult(int callbackType, ScanResult result) {
      take(result);
    }

    @Override
    public void onBatchScanResults(List<ScanResult> results) {
      for (ScanResult result : results) {
        take(result);
      }
    }

    @Override
    public void onScanFailed(int errorCode) {
      listening = false;
      problem = "Android would not listen for the earbuds' broadcast (code " + errorCode + ")";
      log.add("earbuds", problem + "; trying again in a few minutes");
      main.removeCallbacks(afresh);
      main.postDelayed(afresh, RETRY_MS);
    }
  };

  BudsBroadcast(Context app, Handler main, EventLog log, Listener listener) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.listener = listener;
  }

  /** Starts listening, or carries on. Safe to call any number of times. */
  void start() {
    wanted = true;
    begin();
  }

  /** Bluetooth went off: nothing to listen with until {@link #start} is called again. */
  void stop() {
    main.removeCallbacks(afresh);
    end();
    problem = "Bluetooth is off";
  }

  private void take(ScanResult result) {
    try {
      ScanRecord record = result.getScanRecord();
      byte[] data = record == null ? null : record.getServiceData(FAST_PAIR);
      if (data == null) {
        return;
      }
      broadcasts++;
      BudsReading reading = BudsReading.parse(data);
      if (reading == null) {
        return;
      }
      withBatteries++;
      listener.onBudsBroadcast(result.getDevice().getAddress(), result.getRssi(), reading);
    } catch (RuntimeException e) {
      // One broadcast less.
    }
  }

  private void begin() {
    if (!wanted || listening) {
      return;
    }
    try {
      BluetoothManager manager = app.getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      scanner = adapter == null || !adapter.isEnabled() ? null : adapter.getBluetoothLeScanner();
      if (scanner == null) {
        problem = "Bluetooth is off";
        return;
      }
      // Any data under the Fast Pair service: a first byte of anything
      ScanFilter filter = new ScanFilter.Builder().setServiceData(FAST_PAIR, new byte[] {0}, new byte[] {0}).build();
      ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
      scanner.startScan(Collections.singletonList(filter), settings, callback);
      listening = true;
      problem = "";
      starts++;
      main.removeCallbacks(afresh);
      main.postDelayed(afresh, AFRESH_MS);
    } catch (SecurityException e) {
      // Tried again when the app is next woken.
      problem = "waiting for the Nearby devices permission";
    } catch (RuntimeException e) {
      problem = "could not listen for the earbuds' broadcast: " + e.getClass().getSimpleName();
      log.add("earbuds", problem);
      main.removeCallbacks(afresh);
      main.postDelayed(afresh, RETRY_MS);
    }
  }

  private void end() {
    if (!listening) {
      return;
    }
    listening = false;
    try {
      if (scanner != null) {
        scanner.stopScan(callback);
      }
    } catch (RuntimeException e) {
      // It has stopped by itself, then.
    }
  }

  /** Empty if listening works, else what is in the way. */
  String problem() {
    return listening ? "" : problem;
  }

  /** For the report. */
  String dump() {
    return "Listening for earbuds' own broadcasts: " + (listening ? "yes" : "no" + (problem.isEmpty() ? "" : " ("
        + problem + ")")) + ", started " + starts + " time" + (starts == 1 ? "" : "s") + "\nFast Pair broadcasts heard: "
        + broadcasts + ", of them with battery levels: " + withBatteries;
  }
}
