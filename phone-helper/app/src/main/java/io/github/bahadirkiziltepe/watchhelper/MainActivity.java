package io.github.bahadirkiziltepe.watchhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The only screen: the setup steps, the watch updater, buttons to try what Spotify offers, and
 * the report.
 *
 * <p>Built in code rather than from layout files to keep the app to a handful of source files.
 */
public class MainActivity extends Activity {
  private static final int REQUEST_BLUETOOTH = 1;
  private static final int REQUEST_PHONE = 2;
  private static final int LOG_LINES_ON_SCREEN = 60;

  private Core core;

  private TextView gadgetbridgeNote;
  private TextView accessStatus;
  private TextView accessHint;
  private View accessButtons;
  private TextView bluetoothStatus;
  private View bluetoothButtons;
  private TextView watchStatus;
  private Button findButton;
  private TextView backgroundStatus;
  private View backgroundButtons;
  private TextView phoneStatus;
  private Button phoneButton;
  private TextView updateStatus;
  private Button installButton;
  private Button otherBuildsButton;
  private Button checkButton;
  private Button appUpdateButton;
  private TextView spotifyStatus;
  private LinearLayout customButtons;
  private String customSignature;
  private TextView eventsStatus;
  private TextView logView;

  private final Runnable onChange = this::render;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    core = Core.get(this);
    setContentView(buildScreen());
  }

  @Override
  protected void onResume() {
    super.onResume();
    core.addScreen(onChange);
    core.wake();
    core.updater.check(false);
    render();
  }

  @Override
  protected void onPause() {
    core.removeScreen(onChange);
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    core.wake();
    render();
  }

  // ---------------------------------------------------------------- building the screen

  private View buildScreen() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(12), dp(16), dp(40));
    scroll.addView(root,
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    root.addView(small("Version " + core.version()));
    gadgetbridgeNote = body("⚠ Gadgetbridge is still installed. Disconnect the watch in Gadgetbridge, or "
        + "uninstall it. Otherwise both apps act on every button you press on the watch.");
    root.addView(gadgetbridgeNote);

    root.addView(heading("1. Notification access"));
    accessStatus = body("");
    root.addView(accessStatus);
    accessHint = small("If Android says “Restricted setting”: tap App info, then the three dots at the top "
        + "right, then “Allow restricted settings”. Come back and tap Turn on again.");
    root.addView(accessHint);
    accessButtons = row(button("Turn on", v -> openAccessSettings()), button("App info", v -> openAppInfo()));
    root.addView(accessButtons);

    root.addView(heading("2. Bluetooth"));
    bluetoothStatus = body("");
    root.addView(bluetoothStatus);
    bluetoothButtons = row(button("Allow", v -> askForBluetooth()));
    root.addView(bluetoothButtons);

    root.addView(heading("3. Watch"));
    watchStatus = body("");
    root.addView(watchStatus);
    findButton = button("Find watch", v -> core.watch.findWatch());
    root.addView(row(findButton));

    root.addView(heading("4. Keep running"));
    backgroundStatus = body("");
    root.addView(backgroundStatus);
    backgroundButtons = row(button("Allow", v -> askToRunInBackground()));
    root.addView(backgroundButtons);

    root.addView(heading("Notifications and calls"));
    phoneStatus = body("");
    root.addView(phoneStatus);
    phoneButton = button("Allow Phone", v -> requestPermissions(
        new String[] {Manifest.permission.ANSWER_PHONE_CALLS}, REQUEST_PHONE));
    root.addView(row(button("Test notification", v -> core.notifications.sendTest()), phoneButton));

    root.addView(heading("Update watch"));
    updateStatus = body("");
    root.addView(updateStatus);
    installButton = button("Update watch", v -> confirmInstall(core.updater.newest()));
    otherBuildsButton = button("Other builds", v -> chooseBuild());
    checkButton = button("Check", v -> core.updater.check(true));
    root.addView(row(installButton, otherBuildsButton, checkButton));
    appUpdateButton = button("Get phone app update", v -> openLink(core.updater.appUpdateUrl()));
    root.addView(row(appUpdateButton));

    root.addView(heading("Spotify"));
    spotifyStatus = body("");
    root.addView(spotifyStatus);

    root.addView(heading("Try Spotify’s buttons"));
    root.addView(small("Start a song first. Tap each button a few times, a second or two apart, and watch what "
        + "Spotify does."));
    customButtons = new LinearLayout(this);
    customButtons.setOrientation(LinearLayout.VERTICAL);
    root.addView(customButtons);
    root.addView(row(button("Shuffle (standard)", v -> core.spotify.standardShuffle("you")),
        button("Repeat (standard)", v -> core.spotify.standardRepeat("you"))));

    root.addView(heading("Watch buttons"));
    root.addView(small("On the watch: tap, double tap, hold, and swipe left and right for volume."));
    eventsStatus = body("");
    root.addView(eventsStatus);

    root.addView(heading("Report"));
    root.addView(small("When you have tried everything above, send the report back."));
    root.addView(row(button("Copy report", v -> copyReport()), button("Share report", v -> shareReport())));

    root.addView(heading("What happened"));
    logView = new TextView(this);
    logView.setTypeface(Typeface.MONOSPACE);
    logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
    logView.setTextIsSelectable(true);
    root.addView(logView);

    return scroll;
  }

  private TextView heading(String text) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    view.setTypeface(Typeface.DEFAULT_BOLD);
    view.setPadding(0, dp(20), 0, dp(4));
    return view;
  }

  private TextView body(String text) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    view.setPadding(0, dp(2), 0, dp(2));
    return view;
  }

  private TextView small(String text) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    view.setAlpha(0.75f);
    view.setPadding(0, dp(2), 0, dp(2));
    return view;
  }

  private Button button(String label, View.OnClickListener onClick) {
    Button view = new Button(this);
    view.setText(label);
    view.setAllCaps(false);
    view.setOnClickListener(onClick);
    return view;
  }

  private LinearLayout row(View... views) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    for (View view : views) {
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      params.setMarginEnd(dp(8));
      row.addView(view, params);
    }
    return row;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  // ---------------------------------------------------------------- showing the current state

  private void render() {
    if (accessStatus == null) {
      return;
    }
    gadgetbridgeNote.setVisibility(core.gadgetbridgeInstalled() ? View.VISIBLE : View.GONE);

    boolean access = core.hasNotificationAccess();
    accessStatus.setText(access ? "✓ On"
        : "✗ Off. Needed to see what Spotify is playing, and to pass notifications and calls on to the watch.");
    accessHint.setVisibility(access ? View.GONE : View.VISIBLE);
    accessButtons.setVisibility(access ? View.GONE : View.VISIBLE);

    boolean bluetooth = core.watch.hasConnectPermission() && core.watch.hasScanPermission();
    bluetoothStatus.setText(bluetooth ? "✓ Allowed"
        : "✗ Not allowed yet. Tap Allow. If nothing pops up, open App info (step 1), then Permissions, then "
            + "Nearby devices.");
    bluetoothButtons.setVisibility(bluetooth ? View.GONE : View.VISIBLE);

    watchStatus.setText(core.watch.describe());
    // Looking for the watch reopens the connection, which must not happen during an update.
    findButton.setEnabled(bluetooth && !core.updater.busy());

    boolean background = core.mayRunInBackground();
    backgroundStatus.setText(background ? "✓ Android will not put this app to sleep."
        : "✗ Android may put this app to sleep, and the watch would stop getting updates from the phone.");
    backgroundButtons.setVisibility(background ? View.GONE : View.VISIBLE);

    phoneStatus.setText(core.notifications.describe() + "\n" + core.calls.describe());
    phoneButton.setVisibility(core.calls.hasPhonePermission() ? View.GONE : View.VISIBLE);

    renderUpdater();

    spotifyStatus.setText(core.spotify.describe());
    renderCustomButtons();

    eventsStatus.setText(core.watch.describeEvents());
    logView.setText(TextUtils.join("\n", core.log.tail(LOG_LINES_ON_SCREEN)));
  }

  private void renderUpdater() {
    Updater updater = core.updater;
    updateStatus.setText(updater.status());

    Updater.WatchBuild newest = updater.newest();
    boolean idle = !updater.busy();
    boolean linked = core.watch.isReady();
    installButton.setText(newest == null ? "Update watch" : "Install build " + newest.number);
    installButton.setEnabled(idle && linked && newest != null);
    otherBuildsButton.setEnabled(idle && linked && updater.builds().size() > 1);
    checkButton.setEnabled(idle);

    boolean appUpdate = updater.appUpdateUrl() != null;
    appUpdateButton.setVisibility(appUpdate ? View.VISIBLE : View.GONE);
    if (appUpdate) {
      appUpdateButton.setText("Get phone app build " + updater.appUpdateNumber());
    }

    // A watch update takes a few minutes; do not let the screen switch off in the middle of it.
    if (updater.sending()) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  /** Rebuilds the list of Spotify's own buttons, but only when Spotify changed what it offers. */
  private void renderCustomButtons() {
    List<SpotifyWatcher.Custom> customs = core.spotify.customs();
    StringBuilder signature = new StringBuilder();
    for (SpotifyWatcher.Custom custom : customs) {
      signature.append(custom.id).append('|').append(custom.label).append('\n');
    }
    if (signature.toString().equals(customSignature)) {
      return;
    }
    customSignature = signature.toString();
    customButtons.removeAllViews();
    if (customs.isEmpty()) {
      customButtons.addView(small("Spotify is not offering any buttons of its own right now."));
      return;
    }
    for (SpotifyWatcher.Custom custom : customs) {
      final String id = custom.id;
      String label = (custom.label.isEmpty() ? "(no name)" : custom.label) + "\n" + id;
      customButtons.addView(row(button(label, v -> core.spotify.sendCustom(id, "you"))));
    }
  }

  // ---------------------------------------------------------------- watch updates

  private void confirmInstall(Updater.WatchBuild build) {
    if (build == null) {
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Install watch build " + build.number + "?")
        .setMessage((build.summary.isEmpty() ? "" : build.summary + "\n\n")
            + "First, on the watch: swipe up, tap the gear, Over-the-air, and pick Enabled.\n\n"
            + "Then keep the watch next to the phone and leave this screen open. It takes a few minutes.")
        .setPositiveButton("Install", (dialog, which) -> core.updater.install(build))
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void chooseBuild() {
    final List<Updater.WatchBuild> builds = core.updater.builds();
    if (builds.isEmpty()) {
      return;
    }
    CharSequence[] labels = new CharSequence[builds.size()];
    for (int i = 0; i < labels.length; i++) {
      labels[i] = builds.get(i).label();
    }
    new AlertDialog.Builder(this)
        .setTitle("Pick a watch build")
        .setItems(labels, (dialog, which) -> confirmInstall(builds.get(which)))
        .setNegativeButton("Cancel", null)
        .show();
  }

  // ---------------------------------------------------------------- button actions

  private void openAccessSettings() {
    try {
      Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
      intent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
          new ComponentName(this, MediaListenerService.class).flattenToString());
      startActivity(intent);
    } catch (RuntimeException e) {
      try {
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
      } catch (RuntimeException e2) {
        toast("Open Settings, search for “Notification access” and switch on Watch Helper.");
      }
    }
  }

  private void openAppInfo() {
    try {
      startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    } catch (RuntimeException e) {
      toast("Open Settings, then Apps, then Watch Helper.");
    }
  }

  private void askForBluetooth() {
    requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN},
        REQUEST_BLUETOOTH);
  }

  @SuppressLint("BatteryLife") // A watch companion has to stay connected in the background.
  private void askToRunInBackground() {
    try {
      startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:" + getPackageName())));
    } catch (RuntimeException e) {
      toast("Open App info, then Battery, and pick Unrestricted.");
      openAppInfo();
    }
  }

  private void openLink(String url) {
    if (url == null) {
      return;
    }
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (RuntimeException e) {
      toast("Could not open the browser.");
    }
  }

  private void copyReport() {
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    if (clipboard == null) {
      toast("Could not reach the clipboard. Use Share report instead.");
      return;
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("Watch Helper report", Report.build(core)));
    toast("Report copied. Paste it into the chat.");
  }

  private void shareReport() {
    Intent send = new Intent(Intent.ACTION_SEND);
    send.setType("text/plain");
    send.putExtra(Intent.EXTRA_SUBJECT, "Watch Helper report");
    send.putExtra(Intent.EXTRA_TEXT, Report.build(core));
    try {
      startActivity(Intent.createChooser(send, "Send report"));
    } catch (RuntimeException e) {
      toast("Could not open the share menu. Use Copy report instead.");
    }
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }
}
