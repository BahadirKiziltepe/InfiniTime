package io.github.bahadirkiziltepe.watchhelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceController;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

/**
 * Finds the watch builds published on this project's GitHub releases page and installs one on the
 * watch. Also notices when a newer build of this phone app has been published.
 *
 * <p>Safety rules, because the watch is sealed and cannot be rescued by wire:
 * <ul>
 *   <li>Builds are only ever taken from this project's own releases page, over HTTPS.</li>
 *   <li>A file is only sent if it holds exactly one thing: a watch app image. Anything that
 *       claims to carry a bootloader or radio firmware is refused.</li>
 *   <li>The watch itself checks the image before using it, keeps the old build until the new one
 *       is confirmed on the watch, and goes back to the old one otherwise.</li>
 * </ul>
 */
final class Updater {
  interface Listener {
    void onUpdaterChanged();
  }

  /** A watch build that can be installed. */
  static final class WatchBuild {
    final int number;
    final String summary;
    final String published;
    final String url;

    WatchBuild(int number, String summary, String published, String url) {
      this.number = number;
      this.summary = summary;
      this.published = published;
      this.url = url;
    }

    String label() {
      return "Build " + number + (summary.isEmpty() ? "" : ": " + summary) + (published.isEmpty() ? "" : " (" + published + ")");
    }
  }

  private static final String REPOSITORY = "BahadirKiziltepe/InfiniTime";
  private static final String RELEASES_URL = "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=100";
  private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPOSITORY + "/releases/download/";

  private static final Pattern WATCH_TAG = Pattern.compile("^build-(\\d{1,6})(?:-\\d+)?$");
  private static final Pattern WATCH_FILE = Pattern.compile("^watch-build-\\d+\\.zip$");
  private static final Pattern APP_TAG = Pattern.compile("^helper-(\\d{1,6})(?:-\\d+)?$");
  private static final Pattern APP_FILE = Pattern.compile("^watch-helper-\\d+\\.apk$");
  private static final Pattern CODE_LINE = Pattern.compile("Code: `[^`]*` \\((.*)\\)");

  /** The watch's app slot. A bigger image cannot be a valid watch app. */
  private static final long MAX_IMAGE_BYTES = 475136;
  private static final int MAX_LIST_BYTES = 4 * 1024 * 1024;
  private static final int MAX_BUILD_BYTES = 2 * 1024 * 1024;
  private static final int MAX_MANIFEST_BYTES = 64 * 1024;
  private static final long AUTO_CHECK_EVERY_MS = 10 * 60 * 1000;
  private static final long SILENCE_CHECK_MS = 15_000;
  private static final long SILENCE_LIMIT_MS = 90_000;
  /** How long the update library gets to stop after being told to. */
  private static final long STOP_GRACE_MS = 20_000;
  /** After a failed try the watch needs about ten seconds to forget it. */
  private static final long COOL_DOWN_MS = 20_000;
  /** The connection's standard message size. A watch update is only sent while it is this. */
  private static final int STANDARD_MESSAGE_SIZE = 23;
  private static final int INIT_PACKET_BYTES = 14;
  /** The watch stores an update in blocks of this size. See verify(). */
  private static final int WATCH_BLOCK_BYTES = 200;

  private static final String PREFS = "updates";
  private static final String KEY_LAST_INSTALLED = "lastInstalled";

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final Listener listener;
  private final SharedPreferences prefs;
  private final long ownBuild;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  private List<WatchBuild> builds = new ArrayList<>();
  private String appUpdateUrl;
  private int appUpdateNumber = -1;
  private String status = "Not checked yet.";
  private boolean busy;
  private boolean sending;
  private long lastCheckAt;
  private long lastSignalAt;
  private WatchBuild installing;
  private DfuServiceController controller;
  private boolean stopping;
  private boolean stuck;
  private long retryNotBefore;
  // After an update: wait for the watch to restart and link again before talking about Validate.
  private boolean awaitingRelink;
  private boolean sawLinkDrop;
  private int relinkBuild;

  private final Runnable silenceCheck = this::onSilenceCheck;

  Updater(Context app, Handler main, EventLog log, WatchLink watch, long ownBuild, Listener listener) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.watch = watch;
    this.ownBuild = ownBuild;
    this.listener = listener;
    this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  // ---------------------------------------------------------------- state for the screen

  String status() {
    return status;
  }

  /** True while looking, downloading or sending. */
  boolean busy() {
    return busy;
  }

  /** True while a build is on its way to the watch. The screen should stay on. */
  boolean sending() {
    return sending;
  }

  List<WatchBuild> builds() {
    return Collections.unmodifiableList(builds);
  }

  WatchBuild newest() {
    return builds.isEmpty() ? null : builds.get(0);
  }

  /** Where to download a newer build of this phone app, or null if this one is the newest. */
  String appUpdateUrl() {
    return appUpdateUrl;
  }

  int appUpdateNumber() {
    return appUpdateNumber;
  }

  String dump() {
    StringBuilder sb = new StringBuilder();
    sb.append("Status: ").append(status).append('\n');
    sb.append("Watch builds seen: ").append(builds.size());
    if (!builds.isEmpty()) {
      sb.append(" (newest ").append(builds.get(0).number).append(')');
    }
    sb.append('\n');
    sb.append("Last build sent from this app: ").append(lastInstalledText()).append('\n');
    sb.append("Newer phone app available: ").append(appUpdateUrl == null ? "no" : "build " + appUpdateNumber)
        .append('\n');
    return sb.toString();
  }

  private String lastInstalledText() {
    int last = prefs.getInt(KEY_LAST_INSTALLED, -1);
    return last < 0 ? "none" : String.valueOf(last);
  }

  // ---------------------------------------------------------------- looking for builds

  /** Looks at the releases page. Unless the user asked, at most once every ten minutes. */
  void check(boolean userAsked) {
    if (busy) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (!userAsked && lastCheckAt != 0 && now - lastCheckAt < AUTO_CHECK_EVERY_MS) {
      return;
    }
    lastCheckAt = now;
    busy = true;
    setStatus("Looking for watch builds…");
    worker.execute(() -> {
      Found found = null;
      String problem = null;
      try {
        found = parse(new String(fetch(RELEASES_URL, MAX_LIST_BYTES), StandardCharsets.UTF_8));
      } catch (Exception e) {
        problem = describe(e);
      }
      final Found result = found;
      final String failure = problem;
      main.post(() -> onChecked(result, failure));
    });
  }

  private void onChecked(Found found, String problem) {
    busy = false;
    if (found == null) {
      log.add("update", "could not read the releases page: " + problem);
      setStatus("✗ Could not reach GitHub (" + problem + "). Check the phone's internet and tap Check.");
      return;
    }
    builds = found.builds;
    if (found.appNumber > ownBuild && found.appUrl != null) {
      appUpdateNumber = found.appNumber;
      appUpdateUrl = found.appUrl;
    } else {
      appUpdateNumber = -1;
      appUpdateUrl = null;
    }
    log.add("update", "releases page: " + builds.size() + " watch build(s)"
        + (builds.isEmpty() ? "" : ", newest " + builds.get(0).number)
        + (appUpdateUrl == null ? "" : "; newer phone app " + appUpdateNumber));
    setStatus(idleStatus());
  }

  private String idleStatus() {
    if (builds.isEmpty()) {
      return "No watch builds found on the releases page.";
    }
    WatchBuild newest = builds.get(0);
    int last = prefs.getInt(KEY_LAST_INSTALLED, -1);
    String note;
    if (last == newest.number) {
      note = "\nThis is the build last sent from this app.";
    } else if (last < 0) {
      note = "";
    } else {
      note = "\nLast sent from this app: build " + last + ".";
    }
    return "Newest: " + newest.label() + "." + note;
  }

  private static final class Found {
    final List<WatchBuild> builds = new ArrayList<>();
    int appNumber = -1;
    String appUrl;
  }

  private static Found parse(String json) throws Exception {
    Found found = new Found();
    JSONArray releases = new JSONArray(json);
    for (int i = 0; i < releases.length(); i++) {
      JSONObject release = releases.getJSONObject(i);
      if (release.optBoolean("draft", false)) {
        continue;
      }
      String tag = release.optString("tag_name", "");
      JSONArray assets = release.optJSONArray("assets");
      if (assets == null) {
        continue;
      }
      Matcher watchTag = WATCH_TAG.matcher(tag);
      Matcher appTag = APP_TAG.matcher(tag);
      if (watchTag.matches()) {
        int number = Integer.parseInt(watchTag.group(1));
        String url = assetUrl(assets, WATCH_FILE);
        if (url != null && !hasBuild(found.builds, number)) {
          found.builds.add(new WatchBuild(number, summaryOf(release), dateOf(release), url));
        }
      } else if (appTag.matches()) {
        int number = Integer.parseInt(appTag.group(1));
        String url = assetUrl(assets, APP_FILE);
        if (url != null && number > found.appNumber) {
          found.appNumber = number;
          found.appUrl = url;
        }
      }
    }
    Collections.sort(found.builds, (a, b) -> Integer.compare(b.number, a.number));
    return found;
  }

  private static boolean hasBuild(List<WatchBuild> builds, int number) {
    for (WatchBuild build : builds) {
      if (build.number == number) {
        return true;
      }
    }
    return false;
  }

  /** The download address of the release file with the expected name, if it is on this project's page. */
  private static String assetUrl(JSONArray assets, Pattern name) {
    for (int i = 0; i < assets.length(); i++) {
      JSONObject asset = assets.optJSONObject(i);
      if (asset == null) {
        continue;
      }
      String url = asset.optString("browser_download_url", "");
      if (name.matcher(asset.optString("name", "")).matches() && url.startsWith(DOWNLOAD_PREFIX)) {
        return url;
      }
    }
    return null;
  }

  /** What changed in a build: the line the build wrote into the release notes. */
  private static String summaryOf(JSONObject release) {
    Matcher code = CODE_LINE.matcher(release.optString("body", ""));
    return code.find() ? code.group(1).trim() : "";
  }

  private static String dateOf(JSONObject release) {
    try {
      ZonedDateTime when = Instant.parse(release.optString("published_at", "")).atZone(ZoneId.systemDefault());
      return when.format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US));
    } catch (RuntimeException e) {
      return "";
    }
  }

  // ---------------------------------------------------------------- installing a build

  /** Downloads the build, checks it, and sends it to the watch. */
  void install(WatchBuild build) {
    if (busy || build == null) {
      return;
    }
    if (watch.addressForUpdate() == null) {
      setStatus("✗ The watch is not linked. Do step 3 first.");
      return;
    }
    if (stuck) {
      setStatus("✗ An earlier update try never finished. Force stop this app (App info, Force stop), open it "
          + "again, and then try.");
      return;
    }
    long wait = retryNotBefore - SystemClock.elapsedRealtime();
    if (wait > 0) {
      setStatus("The watch needs a moment after the last try. Tap again in " + (wait / 1000 + 1) + " seconds.");
      return;
    }
    if (watch.messageSize() != STANDARD_MESSAGE_SIZE) {
      // The watch's update code needs 20-byte pieces, which only the standard size guarantees.
      setStatus("✗ Another app changed the Bluetooth connection to the watch. Stop Gadgetbridge, switch "
          + "Bluetooth off and on, wait for the watch to link, and try again.");
      return;
    }
    busy = true;
    installing = build;
    awaitingRelink = false;
    log.add("update", "downloading watch build " + build.number);
    setStatus("Downloading watch build " + build.number + "…");
    final File target = new File(app.getCacheDir(), "watch-build-" + build.number + ".zip");
    worker.execute(() -> {
      String problem;
      try {
        byte[] data = fetch(build.url, MAX_BUILD_BYTES);
        try (FileOutputStream out = new FileOutputStream(target)) {
          out.write(data);
        }
        problem = verify(target);
      } catch (Exception e) {
        problem = "the download failed (" + describe(e) + ")";
      }
      final String failure = problem;
      main.post(() -> onDownloaded(target, failure));
    });
  }

  private void onDownloaded(File file, String problem) {
    WatchBuild build = installing;
    if (build == null) {
      busy = false;
      return;
    }
    if (problem != null) {
      busy = false;
      installing = null;
      log.add("update", "build " + build.number + " not sent: " + problem);
      setStatus("✗ Build " + build.number + " was not sent: " + problem + ". The watch is untouched.");
      return;
    }
    String address = watch.addressForUpdate();
    if (address == null) {
      busy = false;
      installing = null;
      setStatus("✗ The watch went out of reach before the update started. Nothing was sent.");
      return;
    }

    log.add("update", "build " + build.number + " passed the checks; sending it to the watch");
    watch.setPaused(true);
    sending = true;
    stopping = false;
    lastSignalAt = SystemClock.elapsedRealtime();
    DfuServiceListenerHelper.registerProgressListener(app, progress);
    try {
      controller = new DfuServiceInitiator(address)
          .setDeviceName("InfiniTime")
          // If the phone happens to be paired with the watch, leave that pairing alone.
          .setKeepBond(true)
          .setForeground(false)
          .setDisableNotification(true)
          // Essential. The watch's update code only copes with pieces of at most 20 bytes
          // (DfuService.cpp, DfuImage::Append). By default this library asks the watch for bigger
          // messages and then sends bigger pieces, which that code would write past its buffer.
          // With the request disabled the library always sends 20 bytes at a time.
          .disableMtuRequest()
          .setZip(file.getAbsolutePath())
          .start(app, UpdateService.class);
    } catch (RuntimeException e) {
      finish(false, "the updater could not start (" + e.getClass().getSimpleName() + ")");
      return;
    }
    setStatus("Connecting to the watch for the update…\nKeep this screen open and the watch close.");
    main.postDelayed(silenceCheck, SILENCE_CHECK_MS);
  }

  private final DfuProgressListener progress = new DfuProgressListenerAdapter() {
    @Override
    public void onDeviceConnecting(String deviceAddress) {
      signal("Connecting to the watch for the update…");
    }

    @Override
    public void onDfuProcessStarting(String deviceAddress) {
      signal("Starting the update…");
    }

    @Override
    public void onProgressChanged(String deviceAddress, int percent, float speed, float avgSpeed, int currentPart,
        int partsTotal) {
      WatchBuild build = installing;
      signal("Sending build " + (build == null ? "" : build.number) + " to the watch: " + percent + "%");
    }

    @Override
    public void onFirmwareValidating(String deviceAddress) {
      signal("The watch is checking the new build…");
    }

    @Override
    public void onDeviceDisconnecting(String deviceAddress) {
      signal("Finishing…");
    }

    @Override
    public void onDfuCompleted(String deviceAddress) {
      finish(true, null);
    }

    @Override
    public void onDfuAborted(String deviceAddress) {
      finish(false, "it was stopped");
    }

    @Override
    public void onError(String deviceAddress, int error, int errorType, String message) {
      finish(false, (message == null ? "error" : message) + " (code " + error + ")");
    }
  };

  private void signal(String text) {
    if (!sending) {
      return;
    }
    lastSignalAt = SystemClock.elapsedRealtime();
    setStatus(text + "\nKeep this screen open and the watch close.");
  }

  /**
   * The update library waits for the watch without a time limit. If nothing has moved for a
   * while, tell it to stop; if it does not even do that, refuse further tries in this run of the
   * app, so a stuck try can never come back to life and send something unattended.
   */
  private void onSilenceCheck() {
    if (!sending) {
      return;
    }
    long quiet = SystemClock.elapsedRealtime() - lastSignalAt;
    if (!stopping && quiet > SILENCE_LIMIT_MS) {
      stopping = true;
      lastSignalAt = SystemClock.elapsedRealtime();
      log.add("update", "nothing has moved for a while; telling the updater to stop");
      try {
        if (controller != null) {
          controller.abort();
        }
      } catch (RuntimeException ignored) {
        // Handled by the grace period below.
      }
      setStatus("The update stopped making progress. Stopping it…");
      main.postDelayed(silenceCheck, SILENCE_CHECK_MS);
    } else if (stopping && quiet > STOP_GRACE_MS) {
      stuck = true;
      finish(false, "the updater stopped answering");
    } else {
      main.postDelayed(silenceCheck, SILENCE_CHECK_MS);
    }
  }

  private void finish(boolean ok, String problem) {
    if (!sending) {
      return;
    }
    main.removeCallbacks(silenceCheck);
    try {
      DfuServiceListenerHelper.unregisterProgressListener(app, progress);
    } catch (RuntimeException ignored) {
      // Nothing to undo.
    }
    WatchBuild build = installing;
    sending = false;
    busy = false;
    installing = null;
    controller = null;
    stopping = false;
    watch.setPaused(false);
    String number = build == null ? "" : String.valueOf(build.number);
    if (ok) {
      if (build != null) {
        prefs.edit().putInt(KEY_LAST_INSTALLED, build.number).apply();
        relinkBuild = build.number;
      }
      awaitingRelink = true;
      sawLinkDrop = !watch.isReady();
      log.add("update", "build " + number + " sent; the watch restarts with it");
      setStatus("✓ Build " + number + " is on the watch. It is restarting and switching over, which takes "
          + "about a minute.\nDo not validate yet. Wait until this app says the watch is linked again.");
    } else {
      retryNotBefore = SystemClock.elapsedRealtime() + COOL_DOWN_MS;
      log.add("update", "build " + number + " did not go through: " + problem);
      if (stuck) {
        setStatus("✗ Build " + number + " did not go through: " + problem + ".\nThe watch keeps the build it "
            + "had. Before trying again, force stop this app (App info, Force stop) and open it again.");
      } else {
        setStatus("✗ Build " + number + " did not go through: " + problem + ".\nThe watch keeps the build it "
            + "had. On the watch: swipe up, tap the gear, Over-the-air must say Enabled. Wait 20 seconds, then try "
            + "again.");
      }
    }
  }

  /**
   * Called whenever the watch link changes. After an update, the advice about Validate is only
   * shown once the watch has restarted and linked again: validating is the one step that cannot
   * be undone, so it should follow a look at the new build, not come before it.
   */
  void onWatchLinkChanged() {
    if (!awaitingRelink) {
      return;
    }
    if (!watch.isReady()) {
      sawLinkDrop = true;
    } else if (sawLinkDrop) {
      awaitingRelink = false;
      log.add("update", "the watch is back and linked after the update");
      setStatus("✓ The watch is back with build " + relinkBuild + " and linked again.\nCheck it first: the "
          + "time, a song, and that swipe up and the gear still open Settings. If all is well, on the watch: swipe "
          + "up, tap the gear, Firmware, Validate.\nIf something is wrong, do not validate. Hold the watch's "
          + "button for about 8 seconds until it restarts, and it goes back to the build it had.");
    }
  }

  // ---------------------------------------------------------------- file checks and downloads

  /**
   * Makes sure the downloaded file is a watch app update and nothing else. Returns null when it
   * is, or a short reason when it is not.
   */
  static String verify(File file) {
    try (ZipFile zip = new ZipFile(file)) {
      ZipEntry manifestEntry = zip.getEntry("manifest.json");
      if (manifestEntry == null) {
        return "it is not a watch update file";
      }
      // Exactly the description, the watch app and its small header. Nothing else may ride along.
      if (zip.size() != 3) {
        return "it holds files that do not belong in a watch update";
      }
      JSONObject manifest;
      try (InputStream in = zip.getInputStream(manifestEntry)) {
        manifest = new JSONObject(new String(readUpTo(in, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8))
            .getJSONObject("manifest");
      }
      for (String forbidden : new String[] {"bootloader", "softdevice", "softdevice_bootloader"}) {
        if (manifest.has(forbidden)) {
          return "it would replace more than the watch app, which is never allowed here";
        }
      }
      JSONObject application = manifest.optJSONObject("application");
      if (application == null) {
        return "it does not contain a watch app";
      }
      ZipEntry image = zip.getEntry(application.optString("bin_file", ""));
      ZipEntry init = zip.getEntry(application.optString("dat_file", ""));
      if (image == null || init == null) {
        return "it is incomplete";
      }
      if (image.getSize() <= 0 || image.getSize() > MAX_IMAGE_BYTES) {
        return "the watch app in it is the wrong size";
      }
      // The watch only switches to a new build if the last block it stores is a partial one. An
      // image that is an exact number of blocks would upload fine and then never be used.
      if (image.getSize() % WATCH_BLOCK_BYTES == 0) {
        return "this build's size trips a flaw in the watch's own updater, so the watch would ignore it. It needs "
            + "rebuilding";
      }
      byte[] header;
      try (InputStream in = zip.getInputStream(init)) {
        header = readUpTo(in, INIT_PACKET_BYTES + 1);
      }
      // The header the watch reads: 14 bytes, declaring exactly one entry in its version list.
      if (header.length != INIT_PACKET_BYTES || header[8] != 1 || header[9] != 0) {
        return "its header is not what the watch expects";
      }
      byte[] head;
      try (InputStream in = zip.getInputStream(image)) {
        head = readUpTo(in, 4);
      }
      // Every watch app image starts with these four bytes; the watch's bootloader looks for them.
      if (head.length < 4 || (head[0] & 0xff) != 0x3d || (head[1] & 0xff) != 0xb8 || (head[2] & 0xff) != 0xf3
          || (head[3] & 0xff) != 0x96) {
        return "what it contains is not a watch app";
      }
      return null;
    } catch (Exception e) {
      return "it could not be read (" + describe(e) + ")";
    }
  }

  private static byte[] readUpTo(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    while (out.size() < limit) {
      int n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
      if (n <= 0) {
        break;
      }
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private static byte[] fetch(String address, int maxBytes) throws IOException {
    HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
    try {
      http.setConnectTimeout(15_000);
      http.setReadTimeout(30_000);
      http.setRequestProperty("User-Agent", "WatchHelper");
      http.setRequestProperty("Accept",
          address.startsWith("https://api.github.com/") ? "application/vnd.github+json" : "application/octet-stream");
      int code = http.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) {
        throw new IOException("GitHub answered " + code);
      }
      try (InputStream in = http.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
          out.write(buffer, 0, n);
          if (out.size() > maxBytes) {
            throw new IOException("the download is larger than expected");
          }
        }
        return out.toByteArray();
      }
    } finally {
      http.disconnect();
    }
  }

  private static String describe(Exception e) {
    String message = e.getMessage();
    return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
  }

  private void setStatus(String text) {
    status = text;
    listener.onUpdaterChanged();
  }
}
