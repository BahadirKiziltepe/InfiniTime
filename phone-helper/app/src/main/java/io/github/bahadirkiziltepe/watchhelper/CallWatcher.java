package io.github.bahadirkiziltepe.watchhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tells the watch when the phone rings or is in a call, and answers or declines a ringing call
 * when the watch asks for it.
 *
 * <p>Calls made through apps (WhatsApp and the like) show up as a call notification with the
 * caller's name and Answer and Decline buttons, and that is all it takes: the app presses the
 * notification's own button.
 *
 * <p>Ordinary phone calls are different. On the phone this was written for (a Samsung Galaxy on
 * Android 17, going by its report of 12 November 2026), nothing an app could see without the Phone
 * permission said that the phone was ringing: no call notification appeared until the call had
 * been answered, and the sound mode did not change the way it does for a WhatsApp call. So for
 * those the app asks Android itself: the Phone permission tells it that the phone rings and lets it answer or
 * hang up, Call logs gives it the number that is calling, and Contacts turns that number into a
 * name.
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
  private static final String EXTRA_HANG_UP = "android.hangUpIntent";
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
    PendingIntent hangUp;
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

  // Ordinary phone calls, as Android reports them once the Phone permission is there.
  private final TelephonyManager telephony;
  private boolean telephonyListening;
  private int phoneState = TelephonyManager.CALL_STATE_IDLE;
  /** Who the ordinary phone call is with: a contact's name, or the number. Never logged. */
  private String phoneCaller = "";
  /** Who the watch last asked to call, and when: the name for that call until Android gives one. */
  private String placedName = "";
  private long placedAt = -PLACED_NAME_MS;
  private static final long PLACED_NAME_MS = 90_000;
  private boolean phoneNumberSeen;
  private String lastPhoneAppShape = "";

  private final TelephonyCallback phoneCallback = new PhoneCallback();

  private final class PhoneCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
    @Override
    public void onCallStateChanged(int state) {
      onPhoneState(state, null);
    }
  }

  /** Android also announces the phone's state to apps, and that announcement carries the number. */
  private final BroadcastReceiver phoneReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
        return;
      }
      String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
      String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
      if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
        onPhoneState(TelephonyManager.CALL_STATE_RINGING, number);
      } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
        onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, number);
      } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
        onPhoneState(TelephonyManager.CALL_STATE_IDLE, null);
      }
    }
  };
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
    this.telephony = app.getSystemService(TelephonyManager.class);
  }

  /** Safe to call again and again: it also picks up permissions that were allowed since last time. */
  void start() {
    if (!started) {
      started = true;
      try {
        if (audio != null) {
          audio.addOnModeChangedListener(app.getMainExecutor(), mode -> evaluate());
        }
      } catch (RuntimeException e) {
        log.add("call", "cannot follow the phone's sound mode: " + e);
      }
      try {
        // Only arrives once the Phone permission is there, so it can be asked for right away.
        app.registerReceiver(phoneReceiver, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            Context.RECEIVER_EXPORTED);
      } catch (RuntimeException e) {
        log.add("call", "cannot listen for the phone's call announcements: " + e);
      }
    }
    if (!telephonyListening && telephony != null && has(Manifest.permission.READ_PHONE_STATE)) {
      try {
        telephony.registerTelephonyCallback(app.getMainExecutor(), phoneCallback);
        telephonyListening = true;
        log.add("call", "following ordinary phone calls");
      } catch (RuntimeException e) {
        log.add("call", "cannot follow ordinary phone calls: " + e);
      }
    }
    evaluate();
  }

  /** The phone's own call state changed. {@code number} is who is calling, when Android says. */
  private void onPhoneState(int state, String number) {
    phoneState = state;
    if (state == TelephonyManager.CALL_STATE_IDLE) {
      phoneCaller = "";
      phoneNumberSeen = false;
    } else if (number != null && !number.isEmpty()) {
      phoneNumberSeen = true;
      String name = contactName(number);
      phoneCaller = name.isEmpty() ? number : name;
    }
    evaluate();
  }

  /** The name the phone's contacts have for a number, or nothing. */
  private String contactName(String number) {
    if (!has(Manifest.permission.READ_CONTACTS)) {
      return "";
    }
    Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
    try (Cursor cursor = app.getContentResolver().query(uri,
        new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        return name == null ? "" : name.trim();
      }
    } catch (RuntimeException e) {
      // No contacts to look in. The number is shown instead.
    }
    return "";
  }

  private boolean has(String permission) {
    return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  // ---------------------------------------------------------------- from the phone

  void onPosted(StatusBarNotification sbn) {
    Notification n = sbn.getNotification();
    Bundle extras = n == null ? null : n.extras;
    int callType = extras == null ? 0 : extras.getInt(EXTRA_CALL_TYPE, 0);
    if (n == null || extras == null || (!Notification.CATEGORY_CALL.equals(n.category) && callType == 0)) {
      if (n != null && extras != null && PHONE_APPS.contains(sbn.getPackageName())) {
        notePhoneAppNotification(sbn, n, extras);
      }
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
      note.hangUp = extras.getParcelable(EXTRA_HANG_UP, PendingIntent.class);
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
        } else if (note.hangUp == null && (lower.contains("hang") || lower.contains("end"))) {
          note.hangUp = action.actionIntent;
        }
      }
    }
    note.incoming = callType == CALL_TYPE_INCOMING || (callType == 0 && note.answer != null);
    note.ongoing = !note.incoming && (callType == CALL_TYPE_ONGOING || sbn.isOngoing());
    note.name = callerName(extras);
    note.shape = note.app + ", " + (callType != 0 ? "standard call notification, type " + callType : "plain notification")
        + ", buttons: " + (buttons.length() == 0 ? "none" : buttons) + ", answer " + (note.answer != null ? "yes" : "no")
        + ", decline " + (note.decline != null ? "yes" : "no") + ", hang up " + (note.hangUp != null ? "yes" : "no")
        + ", name " + (note.name.isEmpty() ? "no" : "yes");
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

  /**
   * The phone app put up something that is not marked as a call. Its outline goes into the log
   * once, in case that is how this phone announces a ringing call: which fields it has, never
   * what is in them.
   */
  private void notePhoneAppNotification(StatusBarNotification sbn, Notification n, Bundle extras) {
    StringBuilder shape = new StringBuilder(sbn.getPackageName());
    shape.append(", kind ").append(n.category == null ? "none" : n.category);
    shape.append(sbn.isOngoing() ? ", stays up" : ", can be swiped");
    shape.append(n.fullScreenIntent != null ? ", opens full screen" : "");
    shape.append(", buttons:");
    if (n.actions == null || n.actions.length == 0) {
      shape.append(" none");
    } else {
      for (Notification.Action action : n.actions) {
        shape.append(" [").append(action == null || action.title == null ? "" : action.title).append(']');
      }
    }
    shape.append(", fields:");
    try {
      for (String key : extras.keySet()) {
        shape.append(' ').append(key);
      }
    } catch (RuntimeException e) {
      shape.append(" unreadable");
    }
    String text = shape.toString();
    if (!text.equals(lastPhoneAppShape)) {
      lastPhoneAppShape = text;
      log.add("call", "phone app notification: " + text);
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
    if (ringingNote() != null || phoneState == TelephonyManager.CALL_STATE_RINGING
        || mode == AudioManager.MODE_RINGTONE) {
      return PhoneFrames.CALL_RINGING;
    }
    // Other apps borrow the "talking" mode for things that are not calls, so that one only counts
    // together with a call notification.
    if (phoneState == TelephonyManager.CALL_STATE_OFFHOOK || mode == AudioManager.MODE_IN_CALL
        || (mode == AudioManager.MODE_IN_COMMUNICATION && ongoingNote() != null)) {
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
      name = note != null && !note.name.isEmpty() ? note.name : phoneCaller;
    } else if (state == PhoneFrames.CALL_ACTIVE) {
      CallNote note = ongoingNote();
      name = note != null && !note.name.isEmpty() ? note.name : !phoneCaller.isEmpty() ? phoneCaller : lastName;
      if (name.isEmpty() && android.os.SystemClock.elapsedRealtime() - placedAt < PLACED_NAME_MS) {
        // A call the watch started: Android has not said who it is with, but the watch list has.
        name = placedName;
      }
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
    return has(Manifest.permission.ANSWER_PHONE_CALLS);
  }

  /** What still has to be allowed for ordinary phone calls to work fully. Empty when nothing. */
  String[] missingPermissions() {
    List<String> missing = new ArrayList<>();
    for (String permission : new String[] {Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE}) {
      if (!has(permission)) {
        missing.add(permission);
      }
    }
    return missing.toArray(new String[0]);
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
    boolean phoneCall = phoneState == TelephonyManager.CALL_STATE_RINGING || note == null
        || PHONE_APPS.contains(note.app);
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

  /**
   * Starts a phone call to one of the people on the watch list. Returns null if Android took the
   * call, otherwise a few words for the watch on why not.
   */
  String place(String name, String number) {
    if (!has(Manifest.permission.CALL_PHONE)) {
      note("watch: call someone from the watch list, but making calls is not allowed yet");
      return "Allow calls first";
    }
    if (state() != PhoneFrames.CALL_NONE) {
      note("watch: call someone from the watch list, but there is a call already");
      return "Already in a call";
    }
    try {
      TelecomManager telecom = app.getSystemService(TelecomManager.class);
      if (telecom == null) {
        return "No phone here";
      }
      telecom.placeCall(Uri.fromParts("tel", number, null), new Bundle());
      placedName = name == null ? "" : name;
      placedAt = android.os.SystemClock.elapsedRealtime();
      note("watch: call someone from the watch list (asked Android)");
      return null;
    } catch (RuntimeException e) {
      log.add("call", "Android would not start the call: " + e.getClass().getSimpleName());
      return "Phone said no";
    }
  }

  /** True when the watch may start calls. */
  boolean mayPlaceCalls() {
    return has(Manifest.permission.CALL_PHONE);
  }

  /** Ends the call that is under way. */
  void hangUp() {
    String what = "hang up";
    if (state() != PhoneFrames.CALL_ACTIVE) {
      note("watch: " + what + ", but there is no call under way");
      return;
    }
    CallNote note = ongoingNote();
    boolean phoneCall = phoneState == TelephonyManager.CALL_STATE_OFFHOOK || note == null
        || PHONE_APPS.contains(note.app);
    if (phoneCall && hasPhonePermission()) {
      try {
        TelecomManager telecom = app.getSystemService(TelecomManager.class);
        if (telecom != null) {
          telecom.endCall();
          note("watch: " + what + " (asked Android)");
          // Same as for answering: if the call is still there in a moment, press the button too.
          main.postDelayed(() -> {
            CallNote still = ongoingNote();
            if (state() == PhoneFrames.CALL_ACTIVE && still != null && still.hangUp != null) {
              press(still.hangUp, still, what);
            }
          }, SECOND_TRY_MS);
          return;
        }
      } catch (RuntimeException e) {
        log.add("call", "Android would not " + what + ": " + e);
      }
    }
    press(note == null ? null : note.hangUp, note, what);
  }

  private void press(CallNote note, boolean answer, String what) {
    press(note == null ? null : (answer ? note.answer : note.decline), note, what);
  }

  private void press(PendingIntent button, CallNote note, String what) {
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
    if (audio == null || (state() != PhoneFrames.CALL_ACTIVE && mode != AudioManager.MODE_IN_CALL
        && mode != AudioManager.MODE_IN_COMMUNICATION)) {
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
      // One of the phone's own steps, like its volume key during a call
      int before = audio.getStreamVolume(stream);
      audio.adjustStreamVolume(stream, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
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
    boolean phone = has(Manifest.permission.ANSWER_PHONE_CALLS) && has(Manifest.permission.READ_PHONE_STATE);
    boolean names = has(Manifest.permission.READ_CALL_LOG) && has(Manifest.permission.READ_CONTACTS);
    if (phone && names) {
      return "✓ Calls: " + now + ".";
    }
    if (phone) {
      return "Calls: " + now + ". Ordinary phone calls reach the watch, but without the caller: Android only "
          + "says who is calling to apps allowed to see Call logs, and the name comes from Contacts.";
    }
    return "✗ Calls: " + now + ". WhatsApp calls work as they are. For ordinary phone calls the Phone permission "
        + "is needed (to know that the phone rings, and to answer or hang up), plus Call logs and Contacts to "
        + "show who is calling.";
  }

  private static String yesNo(boolean value) {
    return value ? "yes" : "no";
  }

  /** For the report. Never includes who is calling. */
  String dump() {
    StringBuilder sb = new StringBuilder();
    int state = state();
    sb.append("Call state: ").append(state == PhoneFrames.CALL_RINGING ? "ringing"
        : state == PhoneFrames.CALL_ACTIVE ? "in a call" : "none").append(", sound mode: ").append(mode()).append('\n');
    sb.append("Allowed: answer calls ").append(yesNo(has(Manifest.permission.ANSWER_PHONE_CALLS)))
        .append(", phone state ").append(yesNo(has(Manifest.permission.READ_PHONE_STATE)))
        .append(", call logs ").append(yesNo(has(Manifest.permission.READ_CALL_LOG)))
        .append(", contacts ").append(yesNo(has(Manifest.permission.READ_CONTACTS))).append('\n');
    sb.append("Following ordinary phone calls: ").append(yesNo(telephonyListening)).append(", phone says: ")
        .append(phoneState == TelephonyManager.CALL_STATE_RINGING ? "ringing"
            : phoneState == TelephonyManager.CALL_STATE_OFFHOOK ? "in a call" : "idle")
        .append(", number given for this call: ").append(yesNo(phoneNumberSeen)).append('\n');
    sb.append("Last other notification from the phone app: ")
        .append(lastPhoneAppShape.isEmpty() ? "none yet" : lastPhoneAppShape).append('\n');
    sb.append("Call notifications showing: ").append(notes.size()).append('\n');
    sb.append("Last call notification seen: ").append(lastShape.isEmpty() ? "none yet" : lastShape).append('\n');
    sb.append("Last thing asked by the watch: ").append(lastAction.isEmpty() ? "nothing yet" : lastAction).append('\n');
    return sb.toString();
  }
}
