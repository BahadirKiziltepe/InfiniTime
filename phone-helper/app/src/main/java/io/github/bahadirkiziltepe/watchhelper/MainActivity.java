package io.github.bahadirkiziltepe.watchhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The app's one screen. From top to bottom: the watch, anything still to set up, what Spotify is
 * playing, the Spotify login for playlists and queue, the people to call from the watch,
 * notifications and calls, watch software, and help.
 *
 * <p>Everything is on this one screen, in cards; nothing is hidden behind tabs. What needs no
 * attention stays small: the setup card is only there while something is missing, and the details
 * meant for finding faults are folded away under Help.
 *
 * <p>Built in code rather than from layout files (see {@link Ui}).
 */
public class MainActivity extends Activity {
  private static final int REQUEST_BLUETOOTH = 1;
  private static final int REQUEST_PHONE = 2;
  private static final int REQUEST_PICK_PERSON = 3;
  private static final int LOG_LINES_ON_SCREEN = 60;

  private Core core;
  private Ui ui;

  private Ui.Status linkStatus;
  private View findRow;
  private TextView findButton;

  private TextView setupCaption;
  private LinearLayout setupCard;
  private int setupShown = -1;

  private TextView songTitle;
  private TextView songArtist;
  private View songChips;
  private TextView chipShuffle;
  private TextView chipRepeat;
  private TextView chipSaved;

  private Ui.Status accountStatus;
  private TextView accountNote;
  private View accountButtons;
  private TextView setUpButton;
  private TextView logoutButton;
  private TextView loginAgainButton;
  private LinearLayout accountSteps;
  private EditText clientIdField;
  private TextView loginButton;
  private boolean accountOpen;

  private LinearLayout peopleList;
  private String peopleShown;
  private TextView peopleWarning;

  private Ui.Status notifyStatus;
  private Ui.Status callStatus;
  private Ui.Status claudeStatus;
  private TextView allowCallsButton;

  private Ui.Status updateStatus;
  private TextView installButton;
  private TextView otherBuildsButton;
  private TextView checkButton;
  private LinearLayout appUpdate;
  private TextView appUpdateText;

  private boolean helpOpen;
  private TextView helpToggle;
  private LinearLayout helpBody;
  private TextView linkDetail;
  private Ui.Flow customButtons;
  private String customSignature;
  private TextView logView;

  private final Runnable onChange = this::render;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    core = Core.get(this);
    ui = new Ui(this);
    getWindow().setBackgroundDrawable(new ColorDrawable(ui.background));
    setContentView(buildScreen());

    // The bars at the top and bottom of the phone's screen take the app's background colour.
    getWindow().setStatusBarColor(ui.background);
    getWindow().setNavigationBarColor(ui.background);
    WindowInsetsController bars = getWindow().getInsetsController();
    if (bars != null) {
      int darkIcons = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
          | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
      bars.setSystemBarsAppearance(ui.night ? 0 : darkIcons, darkIcons);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    // Coming back from the Spotify login in the browser. There is nothing to read from it.
    super.onNewIntent(intent);
    setIntent(intent);
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

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_PICK_PERSON || resultCode != RESULT_OK || data == null || data.getData() == null) {
      return;
    }
    String name = null;
    String number = null;
    try (Cursor cursor = getContentResolver().query(data.getData(), new String[] {
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null,
        null)) {
      if (cursor != null && cursor.moveToFirst()) {
        name = cursor.getString(0);
        number = cursor.getString(1);
      }
    } catch (RuntimeException e) {
      toast("Could not read that contact.");
      return;
    }
    String problem = core.contacts.add(name, number);
    if (problem != null) {
      toast(problem);
    }
    render();
  }

  // ---------------------------------------------------------------- building the screen

  private View buildScreen() {
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(ui.background);
    LinearLayout root = ui.column();
    root.setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(56));
    scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    TextView title = ui.title("Watch Helper");
    title.setPadding(ui.dp(8), 0, ui.dp(8), 0);
    root.addView(title);

    // The watch
    LinearLayout watchCard = ui.card();
    ui.add(root, watchCard, 18);
    linkStatus = ui.new Status();
    watchCard.addView(linkStatus.view);
    findButton = ui.button("Find watch", Ui.TONAL, v -> core.watch.findWatch());
    findRow = ui.add(watchCard, ui.flow(findButton), 14);

    // Whatever is still missing. Filled in by render().
    setupCaption = ui.caption("Finish setting up");
    root.addView(setupCaption);
    setupCard = ui.card();
    root.addView(setupCard);

    // What is playing
    root.addView(ui.caption("Now playing"));
    LinearLayout song = ui.card();
    root.addView(song);
    songTitle = ui.strong("");
    song.addView(songTitle);
    songArtist = ui.add(song, ui.note(""), 2);
    chipShuffle = ui.chip("");
    chipRepeat = ui.chip("");
    chipSaved = ui.chip("");
    songChips = ui.add(song, ui.flow(chipShuffle, chipRepeat, chipSaved), 12);

    // Spotify's web service
    root.addView(ui.caption("Playlists and queue on the watch"));
    LinearLayout account = ui.card();
    root.addView(account);
    accountStatus = ui.new Status();
    account.addView(accountStatus.view);
    accountNote = ui.add(account, ui.note(""), 10);
    setUpButton = ui.button("Set up", Ui.FILLED, v -> {
      accountOpen = true;
      render();
    });
    logoutButton = ui.button("Log out", Ui.TONAL, v -> confirmLogout());
    loginAgainButton = ui.button("Log in again", Ui.FILLED, v -> {
      core.account.startLogin(this);
      render();
    });
    accountButtons = ui.add(account, ui.flow(setUpButton, loginAgainButton, logoutButton), 14);
    accountSteps = ui.column();
    account.addView(accountSteps);
    buildAccountSteps();

    // People
    root.addView(ui.caption("People on the watch"));
    LinearLayout people = ui.card();
    root.addView(people);
    people.addView(ui.note("Only the people you add here show up on the watch, one screen to the right of the "
        + "notifications. Tap a name there, then tap again to call."));
    peopleWarning = ui.add(people, ui.text("Calling from the watch needs the Phone permission. Tap Allow calls below.",
        14, ui.warn), 10);
    peopleList = ui.add(people, ui.column(), 4);
    ui.add(people, ui.flow(ui.button("Add person", Ui.FILLED, v -> pickPerson())), 16);

    // Notifications and calls
    root.addView(ui.caption("Notifications and calls"));
    LinearLayout phone = ui.card();
    root.addView(phone);
    notifyStatus = ui.new Status();
    phone.addView(notifyStatus.view);
    callStatus = ui.new Status();
    ui.add(phone, callStatus.view, 16);
    claudeStatus = ui.new Status();
    ui.add(phone, claudeStatus.view, 16);
    allowCallsButton = ui.button("Allow calls", Ui.FILLED, v -> askForCalls());
    ui.add(phone, ui.flow(allowCallsButton,
        ui.button("Send a test notification", Ui.TONAL, v -> core.notifications.sendTest()),
        ui.button("Test the Claude dot", Ui.TONAL, v -> core.testClaude())), 16);

    // Watch software
    root.addView(ui.caption("Watch software"));
    LinearLayout software = ui.card();
    root.addView(software);
    updateStatus = ui.new Status();
    software.addView(updateStatus.view);
    installButton = ui.button("Update watch", Ui.FILLED, v -> confirmInstall(core.updater.newest()));
    otherBuildsButton = ui.button("Other builds", Ui.TONAL, v -> chooseBuild());
    checkButton = ui.button("Check", Ui.TONAL, v -> core.updater.check(true));
    ui.add(software, ui.flow(installButton, otherBuildsButton, checkButton), 14);
    appUpdate = ui.column();
    software.addView(appUpdate);
    appUpdate.addView(ui.divider());
    appUpdateText = ui.body("");
    appUpdate.addView(appUpdateText);
    ui.add(appUpdate, ui.flow(ui.button("Get it", Ui.FILLED, v -> openLink(core.updater.appUpdateUrl()))), 12);
    ui.add(software, ui.text("This app: version " + core.version(), 13, ui.faint), 16);

    // Help
    root.addView(ui.caption("Help"));
    LinearLayout help = ui.card();
    root.addView(help);
    help.addView(ui.strong("Something not working?"));
    ui.add(help, ui.note("The report says what the app saw, without any message texts or names. Send it along when "
        + "you ask for help."), 4);
    helpToggle = ui.button("Show details", Ui.PLAIN, v -> {
      helpOpen = !helpOpen;
      render();
    });
    ui.add(help, ui.flow(ui.button("Copy report", Ui.TONAL, v -> copyReport()),
        ui.button("Share report", Ui.TONAL, v -> shareReport()), helpToggle), 14);
    helpBody = ui.column();
    help.addView(helpBody);
    helpBody.addView(ui.divider());
    helpBody.addView(ui.strong("Link to the watch"));
    linkDetail = ui.add(helpBody, ui.note(""), 4);
    ui.add(helpBody, ui.strong("Spotify’s own buttons"), 20);
    ui.add(helpBody, ui.note("Start a song first. Each of these presses the button Spotify offers to other apps "
        + "under that name."), 4);
    customButtons = ui.add(helpBody, ui.flow(), 10);
    ui.add(helpBody, ui.flow(ui.button("Shuffle (standard)", Ui.TONAL, v -> core.spotify.standardShuffle("you")),
        ui.button("Repeat (standard)", Ui.TONAL, v -> core.spotify.standardRepeat("you"))), 10);
    ui.add(helpBody, ui.strong("What happened"), 20);
    logView = ui.add(helpBody, ui.text("", 11, ui.soft), 6);
    logView.setTypeface(Typeface.MONOSPACE);
    logView.setTextIsSelectable(true);

    return scroll;
  }

  /** The one-time setup of Spotify's web service, step by step. */
  private void buildAccountSteps() {
    accountSteps.addView(ui.divider());
    accountSteps.addView(step("1", "Open Spotify’s developer site and log in with your Spotify Premium account."));
    ui.add(accountSteps, ui.flow(ui.button("Open developer site", Ui.TONAL,
        v -> openLink(SpotifyAccount.DASHBOARD_URL))), 10);

    ui.add(accountSteps, step("2", "Tap Create app. Name and description can be anything. Under Redirect URIs enter "
        + "exactly the line below, tick Web API, agree to the terms and save."), 20);
    ui.add(accountSteps, ui.code(SpotifyAccount.REDIRECT_URI), 10);
    ui.add(accountSteps, ui.flow(ui.button("Copy", Ui.TONAL,
        v -> copy("Redirect URI", SpotifyAccount.REDIRECT_URI, "Copied. Paste it under Redirect URIs."))), 10);

    ui.add(accountSteps, step("3", "Open the app you just made there, go to its Settings, copy the Client ID and "
        + "paste it here."), 20);
    clientIdField = ui.add(accountSteps, ui.field("Client ID"), 10);
    clientIdField.setText(core.account.clientId());
    loginButton = ui.button("Log in to Spotify", Ui.FILLED, v -> login());
    ui.add(accountSteps, ui.flow(ui.button("Paste", Ui.TONAL, v -> pasteClientId()), loginButton), 10);
    ui.add(accountSteps, ui.note("The login opens in your browser. Spotify asks whether to allow it; say yes and come "
        + "back here."), 12);
  }

  /** One numbered step: the number in a small circle, the instruction next to it. */
  private View step(String number, String instruction) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    TextView badge = ui.text(number, 13, ui.onAccent);
    badge.setGravity(Gravity.CENTER);
    badge.setBackground(ui.round(ui.accent, 12));
    LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24));
    badgeParams.setMarginEnd(ui.dp(12));
    row.addView(badge, badgeParams);
    row.addView(ui.body(instruction), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    return row;
  }

  // ---------------------------------------------------------------- showing the current state

  private void render() {
    if (linkStatus == null) {
      return;
    }
    boolean access = core.hasNotificationAccess();
    boolean bluetooth = core.watch.hasConnectPermission() && core.watch.hasScanPermission();
    boolean background = core.mayRunInBackground();

    renderWatch(bluetooth);
    renderSetup(access, bluetooth, background, core.gadgetbridgeInstalled());
    renderSong();
    renderAccount();
    renderPeople();

    setTopic(notifyStatus, "Notifications", core.notifications.describe());
    setTopic(callStatus, "Calls", core.calls.describe());
    setTopic(claudeStatus, "Claude", core.describeClaude());
    Ui.show(allowCallsButton, core.calls.missingPermissions().length > 0);

    renderUpdater();
    renderHelp();
  }

  private void renderWatch(boolean bluetooth) {
    String phase = core.watch.phase();
    if (core.watch.isReady()) {
      linkStatus.set(ui.good, "Watch connected", "");
    } else if (phase.contains("…")) {
      linkStatus.set(ui.warn, "Connecting to the watch", Ui.plain(phase));
    } else {
      linkStatus.set(phase.startsWith("✗") ? ui.bad : ui.warn, "Watch not connected", Ui.plain(phase));
    }
    Ui.show(findRow, !core.watch.isReady());
    // Looking for the watch reopens the connection, which must not happen during an update.
    Ui.enable(findButton, bluetooth && !core.updater.busy());
  }

  /** The setup card lists only what is still missing, and goes away when nothing is. */
  private void renderSetup(boolean access, boolean bluetooth, boolean background, boolean gadgetbridge) {
    int missing = (access ? 0 : 1) | (bluetooth ? 0 : 2) | (background ? 0 : 4) | (gadgetbridge ? 8 : 0);
    if (missing == setupShown) {
      return;
    }
    setupShown = missing;
    setupCard.removeAllViews();
    if (!access) {
      addSetup("Notification access", "Needed to see what Spotify is playing, and to pass notifications and calls on "
          + "to the watch. If Android says “Restricted setting”: tap App info, then the three dots at the top right, "
          + "then “Allow restricted settings”, and come back.",
          ui.button("Turn on", Ui.FILLED, v -> openAccessSettings()),
          ui.button("App info", Ui.TONAL, v -> openAppInfo()));
    }
    if (!bluetooth) {
      addSetup("Nearby devices", "Lets the app find the watch and stay connected to it. If nothing pops up, open App "
          + "info, then Permissions, then Nearby devices.",
          ui.button("Allow", Ui.FILLED, v -> askForBluetooth()), ui.button("App info", Ui.TONAL, v -> openAppInfo()));
    }
    if (!background) {
      addSetup("Keep running", "Android may put this app to sleep, and the watch would stop hearing from the phone.",
          ui.button("Allow", Ui.FILLED, v -> askToRunInBackground()));
    }
    if (gadgetbridge) {
      addSetup("Gadgetbridge is still installed", "Disconnect the watch in Gadgetbridge, or uninstall it. Otherwise "
          + "both apps act on every button you press on the watch.");
    }
    Ui.show(setupCaption, missing != 0);
    Ui.show(setupCard, missing != 0);
  }

  private void addSetup(String title, String explanation, TextView... buttons) {
    if (setupCard.getChildCount() > 0) {
      setupCard.addView(ui.divider());
    }
    setupCard.addView(ui.strong(title));
    ui.add(setupCard, ui.note(explanation), 4);
    if (buttons.length > 0) {
      ui.add(setupCard, ui.flow(buttons), 12);
    }
  }

  private void renderSong() {
    SpotifyWatcher spotify = core.spotify;
    boolean playing = !spotify.accessDenied() && spotify.attached();
    if (spotify.accessDenied()) {
      songTitle.setText("Waiting for notification access");
      songArtist.setText("");
    } else if (!playing) {
      songTitle.setText("Nothing playing");
      songArtist.setText("Open Spotify and start a song.");
    } else {
      songTitle.setText(spotify.title().isEmpty() ? "(no title)" : spotify.title());
      String artist = spotify.artist();
      songArtist.setText(spotify.isPlaying() ? artist : artist.isEmpty() ? "Paused" : artist + " · paused");
      chipShuffle.setText("Shuffle: " + ButtonStates.shuffleName(spotify.shuffleState()));
      chipRepeat.setText("Repeat: " + ButtonStates.repeatName(spotify.repeatState()));
      chipSaved.setText("Saved: " + ButtonStates.savedName(spotify.savedState()));
    }
    Ui.show(songArtist, songArtist.getText().length() > 0);
    Ui.show(songChips, playing);
  }

  private void renderAccount() {
    SpotifyAccount account = core.account;
    boolean loggedIn = account.loggedIn();
    boolean waiting = account.loggingIn();
    String problem = account.problem();
    if (loggedIn) {
      String name = account.userName();
      accountStatus.set(ui.good, "Connected to Spotify", name.isEmpty() ? "" : "Logged in as " + name);
      String trouble = core.library.lastProblem();
      boolean history = account.mayReadHistory();
      if (waiting) {
        accountNote.setText("Finish the login in the browser, then come back here.");
      } else if (!history) {
        accountNote.setText("The list of played songs on the watch needs one more permission from Spotify. Tap Log in "
            + "again and allow it; nothing else changes.");
      } else {
        accountNote.setText(trouble.isEmpty()
            ? "On the watch: go down from the main screen for the queue. Your playlists are to its right, the "
                + "songs played last to its left."
            : "Last problem: " + trouble);
      }
      accountNote.setTextColor(waiting || (history && trouble.isEmpty()) ? ui.soft : ui.warn);
    } else {
      if (waiting) {
        accountStatus.set(ui.warn, "Waiting for the login", "Finish it in the browser, then come back here.");
      } else {
        accountStatus.set(problem.isEmpty() ? ui.faint : ui.bad, "Not connected to Spotify", problem);
      }
      accountNote.setText("The playlists and the queue come from Spotify’s web service. It takes a one-time setup "
          + "with a Spotify Premium account, about five minutes.");
      accountNote.setTextColor(ui.soft);
    }
    boolean open = !loggedIn && (accountOpen || waiting || !account.clientId().isEmpty());
    Ui.show(setUpButton, !loggedIn && !open);
    Ui.show(logoutButton, loggedIn);
    Ui.show(loginAgainButton, loggedIn && !account.mayReadHistory());
    Ui.show(accountButtons, loggedIn || !open);
    Ui.show(accountSteps, open);
    loginButton.setText(waiting ? "Open the login again" : "Log in to Spotify");
  }

  /** Rebuilds the list of people, but only when it changed. */
  private void renderPeople() {
    Ui.show(peopleWarning, !core.calls.mayPlaceCalls());
    List<ContactStore.Person> all = core.contacts.all();
    String signature = core.contacts.generation() + ":" + all.size();
    if (signature.equals(peopleShown)) {
      return;
    }
    peopleShown = signature;
    peopleList.removeAllViews();
    if (all.isEmpty()) {
      ui.add(peopleList, ui.text("Nobody yet.", 15, ui.faint), 12);
      return;
    }
    for (int i = 0; i < all.size(); i++) {
      final int index = i;
      final ContactStore.Person person = all.get(i);
      LinearLayout row = ui.row();
      LinearLayout words = ui.column();
      words.addView(ui.strong(person.name));
      words.addView(ui.note(person.number));
      row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      if (i > 0) {
        row.addView(ui.iconButton("↑", "Move " + person.name + " up", v -> {
          core.contacts.moveUp(index);
          render();
        }), endButton());
      }
      row.addView(ui.iconButton("✕", "Remove " + person.name, v -> confirmRemove(index, person.name)), endButton());
      ui.add(peopleList, row, 12);
    }
  }

  private LinearLayout.LayoutParams endButton() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
    params.setMarginStart(ui.dp(8));
    return params;
  }

  /** Shows a status text such as "✓ Calls: no call right now." as a topic with its detail below. */
  private void setTopic(Ui.Status status, String topic, String text) {
    String all = Ui.plain(text);
    String detail = all.startsWith(topic + ": ") ? all.substring(topic.length() + 2) : all;
    if (!detail.isEmpty()) {
      detail = Character.toUpperCase(detail.charAt(0)) + detail.substring(1);
    }
    status.set(ui.tone(text), topic, detail);
  }

  private void renderUpdater() {
    Updater updater = core.updater;
    String status = updater.status();
    updateStatus.say(ui.tone(status), Ui.plain(status));

    Updater.WatchBuild newest = updater.newest();
    boolean idle = !updater.busy();
    boolean linked = core.watch.isReady();
    installButton.setText(newest == null ? "Update watch" : "Install build " + newest.number);
    Ui.enable(installButton, idle && linked && newest != null);
    Ui.enable(otherBuildsButton, idle && linked && updater.builds().size() > 1);
    Ui.enable(checkButton, idle);

    boolean newApp = updater.appUpdateUrl() != null;
    Ui.show(appUpdate, newApp);
    if (newApp) {
      appUpdateText.setText("Phone app build " + updater.appUpdateNumber() + " is available.");
    }

    // A watch update takes a few minutes; do not let the screen switch off in the middle of it.
    if (updater.sending()) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  private void renderHelp() {
    Ui.show(helpBody, helpOpen);
    helpToggle.setText(helpOpen ? "Hide details" : "Show details");
    if (!helpOpen) {
      return;
    }
    linkDetail.setText(Ui.plain(core.watch.describe()) + "\n" + core.watch.describeEvents());
    renderCustomButtons();
    logView.setText(TextUtils.join("\n", core.log.tail(LOG_LINES_ON_SCREEN)));
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
      customButtons.addView(ui.text("Spotify is not offering any buttons of its own right now.", 14, ui.faint));
      return;
    }
    for (SpotifyWatcher.Custom custom : customs) {
      final String id = custom.id;
      customButtons.addView(ui.button(custom.label.isEmpty() ? id : custom.label, Ui.TONAL,
          v -> core.spotify.sendCustom(id, "you")));
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
            + "First, on the watch: open the settings screen, tap the gear, Over-the-air, and pick Enabled.\n\n"
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

  // ---------------------------------------------------------------- Spotify login

  private void login() {
    core.account.setClientId(clientIdField.getText().toString());
    if (core.account.clientId().isEmpty()) {
      toast("Paste the Client ID first.");
      return;
    }
    clientIdField.clearFocus();
    core.account.startLogin(this);
    render();
  }

  private void pasteClientId() {
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
    if (clip == null || clip.getItemCount() == 0) {
      toast("Nothing to paste. Copy the Client ID on Spotify’s site first.");
      return;
    }
    CharSequence pasted = clip.getItemAt(0).coerceToText(this);
    clientIdField.setText(pasted == null ? "" : pasted.toString().trim());
  }

  private void confirmLogout() {
    new AlertDialog.Builder(this)
        .setTitle("Log out of Spotify?")
        .setMessage("The watch then shows no playlists and no queue. Playing, pausing and skipping keep working.")
        .setPositiveButton("Log out", (dialog, which) -> {
          core.account.logout();
          render();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  // ---------------------------------------------------------------- people

  private void pickPerson() {
    try {
      startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
          REQUEST_PICK_PERSON);
    } catch (RuntimeException e) {
      toast("Could not open your contacts.");
    }
  }

  private void confirmRemove(int index, String name) {
    new AlertDialog.Builder(this)
        .setTitle("Take " + name + " off the watch?")
        .setPositiveButton("Remove", (dialog, which) -> {
          core.contacts.remove(index);
          render();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void askForCalls() {
    String[] missing = core.calls.missingPermissions();
    if (missing.length > 0) {
      requestPermissions(missing, REQUEST_PHONE);
    }
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

  private void copy(String label, String text, String done) {
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    if (clipboard == null) {
      toast("Could not reach the clipboard.");
      return;
    }
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
    toast(done);
  }

  private void copyReport() {
    copy("Watch Helper report", Report.build(core), "Report copied. Paste it into the chat.");
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
