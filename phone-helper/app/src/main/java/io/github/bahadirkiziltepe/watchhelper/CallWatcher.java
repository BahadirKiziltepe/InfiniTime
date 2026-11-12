package io.github.bahadirkiziltepe.watchhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tells the watch when the phone rings or is in a call, and answers or declines a ringing call
 * when the watch asks for it.
 *
 * <p>It learns about calls in two ways that need no extra permission: the phone app (and WhatsApp
 * and the like) put up a call notification with the caller's name and Answer and Decline buttons,
 * and Android says whether the phone is ringing or in a call. To answer or decline it presses the
 * notification's own button. For ordinary phone calls it can also ask Android directly, which
 * needs the Phone permission; that is tried first when it has been allowed.
 *
 * <p>The caller's name goes to the watch and nowhere else. It is never written to the log or the
 * report.
 */
@SuppressLint("MissingPermission") // Android is only asked to answer or end a call after checking the permission.
final class CallWatcher {
  /** The apps that handle ordinary phone calls. Android itself can answer or end those. */
  private static final Set<String> PHONE_APPS = new HashSet<>(Arrays.asList("com.samsung.android.incallui",
      "com.samsung.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.dialer",
      "com.android.server.telecom"));

  // What a call notification carries when it is built the standard way (Notification.CallStyle).
  private static final String EXTRA_CALL_TYPE = "android.callType";
  private static final String EXTRA_ANSWER = "android.answerIntent";
  private static final String EXTRA_DECLINE = "android.declineIntent";
  private static final String EXTRA_CALL_PERSON = "android.callPerson";
  private static final int CALL_TYPE_INCOMING = 1;
  private static final int CALL_TYPE_ONGOING = 2;
  /** The call stream's number while the call runs over a Bluetooth headset. */
  private static final int STREAM_BLUETOOTH_CALL = 6;

  /** A call that ends often flickers through "nothing" on its way from ringing to answered. */
  private static final long IDLE_SETTLE_MS = 1_000;
  /** If the phone still rings this long after Android was asked, the notification's button is pressed too. */
  private static final long SECOND_TRY_MS = 800;

  private static final class CallNote {
    String app;
    String name;
    boolean incoming;
    boolean ongoing;
    PendingIntent answer;
    PendingIntent decline;
    /** How the notification is built, without anything personal. For the report. */
    String shape;
  }

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final AudioManager audio;
  private final Runnable changed;

  /** Call notifications showing right now, by Android's key for each. */
  private final Map<String, CallNote> notes = new LinkedHashMap<>();
  private boolean started;
  private int sentState = -1;
  private String sentName = "";
  private String lastName = "";
  private String lastShape = "";
  private String lastAction = "";

  private final Runnable settle = this::evaluateNow;

  CallWatcher(Context app, Handler main, EventLog log, WatchLink watch, Runnable changed) {
    this.changed = changed;
    this.app = app;
    this.main = main;
    this.log = log;
    this.watch = watch;
    this.audio = app.getSystemService(AudioManager.class);
  }

  void start() {
    if (started || audio == null) {
      return;
    }
    started = true;
    try {
      audio.addOnModeChangedListener(app.getMainExecutor(), mode -> evaluate());
    } catch (RuntimeException e) {
      log.add("call", "cannot follow the phone's call state: " + e);
    }
    evaluate();
  }

  // ---------------------------------------------------------------- from the phone

  void onPosted(StatusBarNotification sbn) {
    Notification n = sbn.getNotification();
    Bundle extras = n == null ? null : n.extras;
    int callType = extras == null ? 0 : extras.getInt(EXTRA_CALL_TYPE, 0);
    if (n == null || extras == null || (!Notification.CATEGORY_CALL.equals(n.category) && callType == 0)) {
      if (notes.remove(sbn.getKey()) != null) {
        evaluate();
      }
      return;
    }

    CallNote note = new CallNote();
    note.app = sbn.getPackageName();
    try {
      note.answer = extras.getParcelable(EXTRA_ANSWER, PendingIntent.class);
      note.decline = extras.getParcelable(EXTRA_DECLINE, PendingIntent.class);
    } catch (RuntimeException e) {
      // Not built the standard way. The buttons below are looked at instead.
    }
    StringBuilder buttons = new StringBuilder();
    if (n.actions != null) {
      for (Notification.Action action : n.actions) {
        String title = action == null || action.title == null ? "" : action.title.toString();
        String lower = title.toLowerCase(Locale.ROOT);
        if (buttons.length() > 0) {
          buttons.append(" | ");
        }
        buttons.append(title);
        if (action == null || action.actionIntent == null) {
          continue;
        }
        if (note.answer == null && (lower.contains("answer") || lower.contains("accept"))) {
          note.answer = action.actionIntent;
        } else if (note.decline == null && (lower.contains("decline") || lower.contains("reject")
            || lower.contains("dismiss") || lower.contains("ignore"))) {
          note.decline = action.actionIntent;
        }
      }
    }
    note.incoming = callType == CALL_TYPE_INCOMING || (callType == 0 && note.answer != null);
    note.ongoing = !note.incoming && (callType == CALL_TYPE_ONGOING || sbn.isOngoing());
    note.name = callerName(extras);
    note.shape = note.app + ", " + (callType != 0 ? "standard call notification, type " + callType : "plain notification")
        + ", buttons: " + (buttons.length() == 0 ? "none" : buttons) + ", answer " + (note.answer != null ? "yes" : "no")
        + ", decline " + (note.decline != null ? "yes" : "no") + ", name " + (note.name.isEmpty() ? "no" : "yes");
    if (!note.shape.equals(lastShape)) {
      lastShape = note.shape;
      log.add("call", "call notification: " + note.shape);
    }
    notes.put(sbn.getKey(), note);
    evaluate();
  }

  void onRemoved(StatusBarNotification sbn) {
    if (notes.remove(sbn.getKey()) != null) {
      evaluate();
    }
  }

  /** Who is calling, as the call notification names them. */
  private static String callerName(Bundle extras) {
    String person = "";
    try {
      Person who = extras.getParcelable(EXTRA_CALL_PERSON, Person.class);
      if (who != null && who.getName() != null) {
        person = who.getName().toString().trim();
      }
    } catch (RuntimeException e) {
      // No person attached.
    }
    if (!person.isEmpty()) {
      return person;
    }
    String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
    String body = text(extras.getCharSequence(Notification.EXTRA_TEXT));
    // Some phones put "Incoming call" in the title and the caller underneath.
    boolean titleIsALabel = title.toLowerCase(Locale.ROOT).contains("call");
    boolean bodyIsALabel = body.isEmpty() || body.toLowerCase(Locale.ROOT).contains("call");
    if (titleIsALabel && !bodyIsALabel) {
      return body;
    }
    return title;
  }

  private static String text(CharSequence in) {
    return in == null ? "" : in.toString().trim();
  }

  // ---------------------------------------------------------------- working out the state

  private CallNote ringingNote() {
    for (CallNote note : notes.values()) {
      if (note.incoming) {
        return note;
      }
    }
    return null;
  }

  private CallNote ongoingNote() {
    for (CallNote note : notes.values()) {
      if (note.ongoing) {
        return note;
      }
    }
    return null;
  }

  private int mode() {
    try {
      return audio == null ? AudioManager.MODE_NORMAL : audio.getMode();
    } catch (RuntimeException e) {
      return AudioManager.MODE_NORMAL;
    }
  }

  private int state() {
    int mode = mode();
    if (ringingNote() != null || mode == AudioManager.MODE_RINGTONE) {
      return PhoneFrames.CALL_RINGING;
    }
    // Other apps borrow the "talking" mode for things that are not calls, so that one only counts
    // together with a call notification.
    if (mode == AudioManager.MODE_IN_CALL || (mode == AudioManager.MODE_IN_COMMUNICATION && ongoingNote() != null)) {
      return PhoneFrames.CALL_ACTIVE;
    }
    return PhoneFrames.CALL_NONE;
  }

  private void evaluate() {
    main.removeCallbacks(settle);
    if (state() == PhoneFrames.CALL_NONE && sentState > PhoneFrames.CALL_NONE) {
      // Wait a moment before telling the watch that the call is over: it may just be changing hands.
      main.postDelayed(settle, IDLE_SETTLE_MS);
      return;
    }
    evaluateNow();
  }

  private void evaluateNow() {
    main.removeCallbacks(settle);
    int state = state();
    String name = "";
    if (state == PhoneFrames.CALL_RINGING) {
      CallNote note = ringingNote();
      name = note == null ? "" : note.name;
    } else if (state == PhoneFrames.CALL_ACTIVE) {
      CallNote note = ongoingNote();
      name = note != null && !note.name.isEmpty() ? note.name : lastName;
    }
    if (!name.isEmpty()) {
      lastName = name;
    }
    if (state == PhoneFrames.CALL_NONE) {
      lastName = "";
    }
    if (state == sentState && name.equals(sentName)) {
      return;
    }
    boolean stateChanged = state != sentState;
    sentState = state;
    sentName = name;
    watch.sendPhone(PhoneFrames.call(state, name));
    changed.run();
    if (stateChanged) {
      log.add("call", state == PhoneFrames.CALL_RINGING ? "the phone is ringing"
          : state == PhoneFrames.CALL_ACTIVE ? "in a call" : "no call");
    }
  }

  void onWatchReady() {
    // A watch that has just linked knows nothing about the call.
    sentState = -1;
    sentName = "";
    evaluateNow();
  }

  // ---------------------------------------------------------------- from the watch

  boolean hasPhonePermission() {
    return app.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
  }

  void answer() {
    act(true);
  }

  void decline() {
    act(false);
  }

  private void act(boolean answer) {
    String what = answer ? "answer" : "decline";
    if (state() != PhoneFrames.CALL_RINGING) {
      note("watch: " + what + ", but the phone is not ringing");
      return;
    }
    CallNote note = ringingNote();
    boolean phoneCall = note == null || PHONE_APPS.contains(note.app);
    if (phoneCall && hasPhonePermission()) {
      try {
        TelecomManager telecom = app.getSystemService(TelecomManager.class);
        if (telecom != null) {
          if (answer) {
            telecom.acceptRingingCall();
          } else {
            telecom.endCall();
          }
          note("watch: " + what + " (asked Android)");
          // Android does not say whether that worked. If the phone is still ringing in a moment,
          // press the button on the call notification as well.
          main.postDelayed(() -> {
            if (state() == PhoneFrames.CALL_RINGING && ringingNote() != null) {
              press(ringingNote(), answer, what);
            }
          }, SECOND_TRY_MS);
          return;
        }
      } catch (RuntimeException e) {
        log.add("call", "Android would not " + what + ": " + e);
      }
    }
    press(note, answer, what);
  }

  private void press(CallNote note, boolean answer, String what) {
    PendingIntent button = note == null ? null : (answer ? note.answer : note.decline);
    if (button == null) {
      note("watch: " + what + ", but there is no way to do it: "
          + (note == null ? "no call notification was seen" : "the call notification has no such button")
          + (hasPhonePermission() ? "" : ", and the Phone permission is not allowed"));
      return;
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Answering may bring up the call screen. Say that this is allowed even though this app is not on screen.
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        button.send(app, 0, null, null, null, null, options.toBundle());
      } else {
        button.send();
      }
      note("watch: " + what + " (pressed the call notification's button)");
    } catch (PendingIntent.CanceledException | RuntimeException e) {
      note("watch: " + what + " failed: " + e);
    }
  }

  private void note(String text) {
    lastAction = text;
    log.add("call", text);
    changed.run();
  }

  /** One step of the call's own volume, which is separate from the music's. */
  void volume(boolean up) {
    int mode = mode();
    if (audio == null || (mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION)) {
      note("watch: call volume " + (up ? "up" : "down") + ", but there is no call");
      return;
    }
    int stream = AudioManager.STREAM_VOICE_CALL;
    try {
      AudioDeviceInfo device = audio.getCommunicationDevice();
      if (device != null && (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
          || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
        stream = STREAM_BLUETOOTH_CALL;
      }
      int before = audio.getStreamVolume(stream);
      int target = VolumeSteps.next(before, audio.getStreamMinVolume(stream), audio.getStreamMaxVolume(stream), up);
      audio.setStreamVolume(stream, target, 0);
      note("watch: call volume " + (up ? "up" : "down") + " (" + before + " to " + audio.getStreamVolume(stream) + " of "
          + audio.getStreamMaxVolume(stream) + ")");
    } catch (RuntimeException e) {
      note("watch: call volume change failed: " + e);
    }
  }

  // ---------------------------------------------------------------- status text

  String describe() {
    int state = state();
    String now = state == PhoneFrames.CALL_RINGING ? "the phone is ringing"
        : state == PhoneFrames.CALL_ACTIVE ? "in a call" : "no call right now";
    String way = hasPhonePermission() ? "✓ Calls: " : "Calls: ";
    return way + now + (hasPhonePermission() ? "."
        : ". The watch answers and declines by pressing the buttons on the call notification. Allowing Phone "
            + "below lets it ask Android directly as well, which is the surer way for ordinary phone calls.");
  }

  /** For the report. Never includes who is calling. */
  String dump() {
    StringBuilder sb = new StringBuilder();
    int state = state();
    sb.append("Call state: ").append(state == PhoneFrames.CALL_RINGING ? "ringing"
        : state == PhoneFrames.CALL_ACTIVE ? "in a call" : "none").append(", sound mode: ").append(mode()).append('\n');
    sb.append("Phone permission: ").append(hasPhonePermission() ? "yes" : "no").append('\n');
    sb.append("Call notifications showing: ").append(notes.size()).append('\n');
    sb.append("Last call notification seen: ").append(lastShape.isEmpty() ? "none yet" : lastShape).append('\n');
    sb.append("Last thing asked by the watch: ").append(lastAction.isEmpty() ? "nothing yet" : lastAction).append('\n');
    return sb.toString();
  }
}
