package io.github.bahadirkiziltepe.watchhelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Follows Spotify's player on this phone: what is playing, and which buttons Spotify offers to
 * other apps. Everything here runs on the main thread.
 *
 * <p>Android only lets an app see other apps' players while it holds notification access, which
 * is why the helper asks for that even though it does not read any notification here.
 */
final class SpotifyWatcher {
  static final String SPOTIFY = "com.spotify.music";

  private static final long FOLLOW_UP_MS = 1500;
  /** How long a play/pause press is trusted before Spotify has confirmed the new state. */
  private static final long ASSUME_MS = 1200;

  interface Listener {
    void onSpotifyChanged();
  }

  /** One of Spotify's own player buttons, as it offers them to other apps. */
  static final class Custom {
    final String id;
    final String label;
    final String iconName;
    final String extras;
    final int kind;
    final PlaybackState.CustomAction raw;

    Custom(String id, String label, String iconName, String extras, PlaybackState.CustomAction raw) {
      this.id = id;
      this.label = label;
      this.iconName = iconName;
      this.extras = extras;
      this.kind = ActionPicker.kind(id, label);
      this.raw = raw;
    }
  }

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final Listener listener;
  private final MediaSessionManager manager;
  private final ComponentName component;

  private boolean listening;
  private boolean accessDenied = true;
  private List<String> sessionPackages = new ArrayList<>();

  private MediaController controller;
  private MediaControllerCompat compat;
  private Resources spotifyResources;

  private int state = PlaybackState.STATE_NONE;
  private long positionMs = -1;
  private long positionAt;
  private float speed = 1f;
  private long actions;
  private List<Custom> customs = new ArrayList<>();
  private String title = "";
  private String artist = "";
  private String album = "";
  private long durationMs;
  private int shuffleMode = PlaybackStateCompat.SHUFFLE_MODE_INVALID;
  private int repeatMode = PlaybackStateCompat.REPEAT_MODE_INVALID;
  private boolean compatReady;
  private int ratingType;
  private String signature = "";

  // What Spotify's shuffle, repeat and save buttons show right now (see ButtonStates).
  private int shuffleState = ButtonStates.UNKNOWN;
  private int repeatState = ButtonStates.UNKNOWN;
  private int savedState = ButtonStates.UNKNOWN;

  // The state a play/pause press just asked for, so two quick presses toggle twice.
  private boolean assumedPlaying;
  private long assumedUntil;

  SpotifyWatcher(Context app, Handler main, EventLog log, Listener listener) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.listener = listener;
    this.manager = app.getSystemService(MediaSessionManager.class);
    this.component = new ComponentName(app, MediaListenerService.class);
  }

  // ---------------------------------------------------------------- lifecycle

  /** Starts (or re-checks) following Spotify. Safe to call any number of times. */
  void start() {
    if (manager == null) {
      return;
    }
    if (!listening) {
      try {
        manager.addOnActiveSessionsChangedListener(sessionsListener, component, main);
        listening = true;
        if (accessDenied) {
          log.add("spotify", "can see the phone's media players now");
        }
        accessDenied = false;
      } catch (RuntimeException e) {
        // Android refuses until notification access is switched on.
        if (!accessDenied) {
          log.add("spotify", "lost notification access");
        }
        accessDenied = true;
        listener.onSpotifyChanged();
        return;
      }
    }
    List<MediaController> sessions;
    try {
      sessions = manager.getActiveSessions(component);
    } catch (RuntimeException e) {
      log.add("spotify", "could not list the phone's media players: " + e.getClass().getSimpleName());
      accessDenied = true;
      stop();
      return;
    }
    onSessions(sessions);
  }

  /** Called when notification access goes away. */
  void stop() {
    if (listening) {
      try {
        manager.removeOnActiveSessionsChangedListener(sessionsListener);
      } catch (RuntimeException ignored) {
        // Nothing to undo.
      }
      listening = false;
    }
    detach("stopped following Spotify");
    listener.onSpotifyChanged();
  }

  private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::onSessions;

  private void onSessions(List<MediaController> controllers) {
    List<String> names = new ArrayList<>();
    MediaController found = null;
    if (controllers != null) {
      for (MediaController c : controllers) {
        String pkg = c.getPackageName();
        names.add(pkg);
        if (found == null && SPOTIFY.equals(pkg)) {
          found = c;
        }
      }
    }
    sessionPackages = names;

    if (found == null) {
      if (controller != null) {
        detach("Spotify's player went away");
      }
      listener.onSpotifyChanged();
    } else if (controller == null || !found.getSessionToken().equals(controller.getSessionToken())) {
      attach(found);
    } else {
      readAll();
    }
  }

  private void attach(MediaController found) {
    detach(null);
    controller = found;
    found.registerCallback(frameworkCallback, main);
    try {
      compat = new MediaControllerCompat(app, MediaSessionCompat.Token.fromToken(found.getSessionToken()));
      compat.registerCallback(compatCallback, main);
    } catch (Exception e) {
      compat = null;
      log.add("spotify", "standard shuffle/repeat channel not available: " + e);
    }
    log.add("spotify", "found Spotify's player");
    readAll();
  }

  private void detach(String why) {
    boolean had = controller != null;
    if (controller != null) {
      try {
        controller.unregisterCallback(frameworkCallback);
      } catch (RuntimeException ignored) {
        // Already gone.
      }
    }
    if (compat != null) {
      try {
        compat.unregisterCallback(compatCallback);
      } catch (RuntimeException ignored) {
        // Already gone.
      }
    }
    main.removeCallbacks(followUp);
    controller = null;
    compat = null;
    state = PlaybackState.STATE_NONE;
    positionMs = -1;
    positionAt = 0;
    speed = 1f;
    actions = 0;
    customs = new ArrayList<>();
    title = "";
    artist = "";
    album = "";
    durationMs = 0;
    shuffleMode = PlaybackStateCompat.SHUFFLE_MODE_INVALID;
    repeatMode = PlaybackStateCompat.REPEAT_MODE_INVALID;
    compatReady = false;
    ratingType = 0;
    shuffleState = ButtonStates.UNKNOWN;
    repeatState = ButtonStates.UNKNOWN;
    savedState = ButtonStates.UNKNOWN;
    if (had && why != null) {
      signature = "";
      log.add("spotify", why);
    }
  }

  private final MediaController.Callback frameworkCallback = new MediaController.Callback() {
    @Override
    public void onPlaybackStateChanged(PlaybackState newState) {
      applyState(newState);
      finishRead();
    }

    @Override
    public void onMetadataChanged(MediaMetadata metadata) {
      applyMetadata(metadata);
      finishRead();
    }

    @Override
    public void onExtrasChanged(Bundle extras) {
      finishRead();
    }

    @Override
    public void onSessionEvent(String event, Bundle extras) {
      log.add("spotify", "event from Spotify: " + event + " " + describeBundle(extras));
    }

    @Override
    public void onSessionDestroyed() {
      detach("Spotify closed its player");
      listener.onSpotifyChanged();
    }
  };

  private final MediaControllerCompat.Callback compatCallback = new MediaControllerCompat.Callback() {
    @Override
    public void onSessionReady() {
      finishRead();
    }

    @Override
    public void onShuffleModeChanged(int mode) {
      finishRead();
    }

    @Override
    public void onRepeatModeChanged(int mode) {
      finishRead();
    }
  };

  // ---------------------------------------------------------------- reading

  /** Asks Spotify's player for everything. Used when first found and after a button press. */
  private void readAll() {
    MediaController c = controller;
    if (c == null) {
      return;
    }
    try {
      applyState(c.getPlaybackState());
      applyMetadata(c.getMetadata());
      ratingType = c.getRatingType();
    } catch (RuntimeException e) {
      log.add("spotify", "could not read Spotify's player: " + e);
    }
    finishRead();
  }

  private void applyState(PlaybackState st) {
    if (st != null) {
      state = st.getState();
      positionMs = st.getPosition();
      positionAt = st.getLastPositionUpdateTime();
      speed = st.getPlaybackSpeed();
      actions = st.getActions();
      customs = readCustoms(st.getCustomActions());
    } else {
      state = PlaybackState.STATE_NONE;
      positionMs = -1;
      positionAt = 0;
      speed = 1f;
      actions = 0;
      customs = new ArrayList<>();
    }
  }

  private void applyMetadata(MediaMetadata md) {
    if (md != null) {
      title = firstText(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
      artist = firstText(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
          MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
      album = firstText(md, MediaMetadata.METADATA_KEY_ALBUM);
      durationMs = md.containsKey(MediaMetadata.METADATA_KEY_DURATION)
          ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
    } else {
      title = "";
      artist = "";
      album = "";
      durationMs = 0;
    }
  }

  /** Picks up the standard shuffle/repeat values, notes any change in the log and tells the app. */
  private void finishRead() {
    if (controller == null) {
      return;
    }
    MediaControllerCompat c = compat;
    if (c != null) {
      try {
        compatReady = c.isSessionReady();
        shuffleMode = c.getShuffleMode();
        repeatMode = c.getRepeatMode();
      } catch (RuntimeException e) {
        log.add("spotify", "could not read standard shuffle/repeat: " + e);
      }
    }
    readButtonStates();
    String now = signatureLine();
    if (!now.equals(signature)) {
      signature = now;
      log.add("spotify", now);
    }
    listener.onSpotifyChanged();
  }

  /**
   * Works out what the shuffle, repeat and save buttons show. Spotify's own buttons say the most
   * (they are the only place smart shuffle and "saved" appear) and change the instant something is
   * pressed; the standard values fill in if a button is missing.
   */
  private void readButtonStates() {
    int shuffle = ButtonStates.UNKNOWN;
    int repeat = ButtonStates.UNKNOWN;
    int saved = ButtonStates.UNKNOWN;
    for (Custom custom : customs) {
      if (custom.kind == ActionPicker.SHUFFLE && shuffle == ButtonStates.UNKNOWN) {
        shuffle = ButtonStates.shuffle(custom.id, custom.iconName);
      } else if (custom.kind == ActionPicker.REPEAT && repeat == ButtonStates.UNKNOWN) {
        repeat = ButtonStates.repeat(custom.id, custom.iconName);
      } else if (custom.kind == ActionPicker.ADD && saved == ButtonStates.UNKNOWN) {
        saved = ButtonStates.saved(custom.id, custom.label, custom.iconName);
      }
    }
    if (shuffle == ButtonStates.UNKNOWN) {
      if (shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE) {
        shuffle = ButtonStates.SHUFFLE_OFF;
      } else if (shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
          || shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_GROUP) {
        shuffle = ButtonStates.SHUFFLE_ON;
      }
    }
    if (repeat == ButtonStates.UNKNOWN) {
      if (repeatMode == PlaybackStateCompat.REPEAT_MODE_NONE) {
        repeat = ButtonStates.REPEAT_OFF;
      } else if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) {
        repeat = ButtonStates.REPEAT_ONE;
      } else if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL
          || repeatMode == PlaybackStateCompat.REPEAT_MODE_GROUP) {
        repeat = ButtonStates.REPEAT_ALL;
      }
    }
    shuffleState = shuffle;
    repeatState = repeat;
    savedState = saved;
  }

  private List<Custom> readCustoms(List<PlaybackState.CustomAction> raw) {
    List<Custom> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    for (PlaybackState.CustomAction a : raw) {
      if (a == null || a.getAction() == null) {
        continue;
      }
      String label = a.getName() == null ? "" : a.getName().toString();
      out.add(new Custom(a.getAction(), label, iconName(a.getIcon()), describeBundle(a.getExtras()), a));
    }
    return out;
  }

  /** The name Spotify gave the button's picture. Often says more about the state than the label. */
  private String iconName(int resourceId) {
    if (resourceId == 0) {
      return "-";
    }
    try {
      if (spotifyResources == null) {
        spotifyResources = app.getPackageManager().getResourcesForApplication(SPOTIFY);
      }
      return spotifyResources.getResourceEntryName(resourceId);
    } catch (Exception e) {
      return "#" + Integer.toHexString(resourceId);
    }
  }

  private static String firstText(MediaMetadata md, String... keys) {
    for (String key : keys) {
      if (md.containsKey(key)) {
        CharSequence text = md.getText(key);
        if (text != null && text.length() > 0) {
          return text.toString();
        }
      }
    }
    return "";
  }

  // ---------------------------------------------------------------- state for the rest of the app

  boolean accessDenied() {
    return accessDenied;
  }

  boolean attached() {
    return controller != null;
  }

  String title() {
    return title;
  }

  String artist() {
    return artist;
  }

  String album() {
    return album;
  }

  long durationMs() {
    return durationMs;
  }

  /** Buffering counts as playing: the next tap on play/pause should pause. */
  boolean isPlaying() {
    return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
  }

  /** Where the song is right now, or -1 if Spotify has not said. */
  long currentPositionMs() {
    if (positionMs < 0) {
      return -1;
    }
    if (state == PlaybackState.STATE_PLAYING && positionAt > 0) {
      long since = SystemClock.elapsedRealtime() - positionAt;
      if (since > 0) {
        return positionMs + (long) (since * speed);
      }
    }
    return positionMs;
  }

  float speed() {
    return speed;
  }

  int shuffleState() {
    return shuffleState;
  }

  int repeatState() {
    return repeatState;
  }

  int savedState() {
    return savedState;
  }

  List<Custom> customs() {
    return Collections.unmodifiableList(customs);
  }

  boolean standardShuffleOffered() {
    return (actions & PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) != 0;
  }

  boolean standardRepeatOffered() {
    return (actions & PlaybackStateCompat.ACTION_SET_REPEAT_MODE) != 0;
  }

  /** A few lines for the screen. */
  String describe() {
    if (accessDenied) {
      return "Waiting for step 1.";
    }
    if (controller == null) {
      return "✗ Spotify is not playing anything.\nOpen Spotify and start a song.";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("✓ ").append(isPlaying() ? "Playing" : stateName(state).toLowerCase(Locale.ROOT));
    sb.append(": ").append(title.isEmpty() ? "(no title)" : title);
    if (!artist.isEmpty()) {
      sb.append(" – ").append(artist);
    }
    sb.append("\nShuffle: ").append(ButtonStates.shuffleName(shuffleState));
    sb.append("   Repeat: ").append(ButtonStates.repeatName(repeatState));
    sb.append("   Saved: ").append(ButtonStates.savedName(savedState));
    return sb.toString();
  }

  /** Everything known, for the report. */
  String dump() {
    StringBuilder sb = new StringBuilder();
    sb.append("Players on this phone: ").append(sessionPackages.isEmpty() ? "none" : sessionPackages).append('\n');
    if (accessDenied) {
      sb.append("Cannot look: notification access is off.\n");
      return sb.toString();
    }
    MediaController c = controller;
    if (c == null) {
      sb.append("Spotify's player: not found\n");
      return sb.toString();
    }
    sb.append("Spotify's player: found\n");
    long now = currentPositionMs();
    sb.append("State: ").append(stateName(state)).append(" (").append(state).append("), position ")
        .append(now < 0 ? "unknown" : (now / 1000) + "s").append(" of ").append(durationMs / 1000).append("s, speed ")
        .append(speed).append('\n');
    sb.append("Track: ").append(title).append('\n');
    sb.append("Artist: ").append(artist).append('\n');
    sb.append("Album: ").append(album).append('\n');
    sb.append("Controls offered (0x").append(Long.toHexString(actions)).append("): ").append(actionNames(actions))
        .append('\n');
    sb.append("Standard channel ready: ").append(compatReady ? "yes" : "no").append('\n');
    sb.append("Standard shuffle: ").append(shuffleMode).append(" (").append(shuffleName(shuffleMode)).append(")")
        .append(standardShuffleOffered() ? ", can be set" : ", setting not offered").append('\n');
    sb.append("Standard repeat: ").append(repeatMode).append(" (").append(repeatName(repeatMode)).append(")")
        .append(standardRepeatOffered() ? ", can be set" : ", setting not offered").append('\n');
    sb.append("Rating type: ").append(ratingType).append('\n');
    sb.append("Read from Spotify's buttons: shuffle ").append(ButtonStates.shuffleName(shuffleState))
        .append(", repeat ").append(ButtonStates.repeatName(repeatState)).append(", saved ")
        .append(ButtonStates.savedName(savedState)).append('\n');

    sb.append("Spotify's own buttons (").append(customs.size()).append("):\n");
    int n = 1;
    for (Custom custom : customs) {
      sb.append("  ").append(n++).append(". id=").append(custom.id).append(" | label=").append(custom.label)
          .append(" | picture=").append(custom.iconName).append(" | extras=").append(custom.extras)
          .append(" | guess=").append(ActionPicker.name(custom.kind)).append('\n');
    }

    try {
      MediaMetadata md = c.getMetadata();
      sb.append("Song info fields:\n");
      if (md == null) {
        sb.append("  none\n");
      } else {
        for (String key : new TreeSet<>(md.keySet())) {
          sb.append("  ").append(shortKey(key)).append(" = ").append(describeField(md, key)).append('\n');
        }
      }
      sb.append("Player extras: ").append(describeBundle(c.getExtras())).append('\n');
      PlaybackState st = c.getPlaybackState();
      sb.append("State extras: ").append(describeBundle(st == null ? null : st.getExtras())).append('\n');
      MediaController.PlaybackInfo info = c.getPlaybackInfo();
      if (info != null) {
        sb.append("Volume: ").append(info.getCurrentVolume()).append(" of ").append(info.getMaxVolume()).append('\n');
      }
    } catch (RuntimeException e) {
      sb.append("(reading details failed: ").append(e).append(")\n");
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- play, pause, next, previous

  /**
   * Plays if paused and pauses if playing. What to do is decided here, from what Spotify is really
   * doing, so it does not matter whether the watch knew the state when the button was pressed.
   */
  void playPause(String from) {
    MediaController c = controller;
    if (c == null) {
      log.add("control", from + ": play/pause (Spotify's player is not open, so sent as a headset button)");
      mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
      return;
    }
    long now = SystemClock.elapsedRealtime();
    boolean playingNow = now < assumedUntil ? assumedPlaying : isPlaying();
    log.add("control", from + ": " + (playingNow ? "pause" : "play"));
    try {
      if (playingNow) {
        c.getTransportControls().pause();
      } else {
        c.getTransportControls().play();
      }
      assumedPlaying = !playingNow;
      assumedUntil = now + ASSUME_MS;
    } catch (RuntimeException e) {
      log.add("control", "play/pause failed: " + e);
    }
  }

  void next(String from) {
    MediaController c = controller;
    log.add("control", from + ": next");
    if (c == null) {
      mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
      return;
    }
    try {
      c.getTransportControls().skipToNext();
    } catch (RuntimeException e) {
      log.add("control", "next failed: " + e);
    }
  }

  void previous(String from) {
    MediaController c = controller;
    log.add("control", from + ": previous");
    if (c == null) {
      mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
      return;
    }
    try {
      c.getTransportControls().skipToPrevious();
    } catch (RuntimeException e) {
      log.add("control", "previous failed: " + e);
    }
  }

  /** The same thing a headset button does: Android passes it to whichever app played last. */
  private void mediaKey(int keyCode) {
    try {
      AudioManager audio = app.getSystemService(AudioManager.class);
      if (audio == null) {
        return;
      }
      long now = SystemClock.uptimeMillis();
      audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
      audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    } catch (RuntimeException e) {
      log.add("control", "headset button failed: " + e);
    }
  }

  // ---------------------------------------------------------------- pressing buttons

  /**
   * Does what the watch's shuffle, repeat or + button should do, using Spotify's own button for it
   * when there is one and the standard media controls otherwise.
   */
  void press(int kind, String from) {
    for (Custom custom : customs) {
      if (custom.kind == kind) {
        sendCustom(custom.id, from);
        return;
      }
    }
    if (kind == ActionPicker.SHUFFLE) {
      standardShuffle(from);
    } else if (kind == ActionPicker.REPEAT) {
      standardRepeat(from);
    } else {
      log.add("try", from + ": Spotify offers no button for \"" + ActionPicker.name(kind) + "\" right now");
    }
  }

  /**
   * Saves the current song, and only ever saves. When the song is already saved the button is
   * left alone, so a stray tap on the watch cannot remove a song from the library.
   */
  void saveCurrentSong(String from) {
    if (savedState == ButtonStates.SAVED_YES) {
      log.add("try", from + ": this song is already saved; left as it is");
      return;
    }
    for (Custom custom : customs) {
      if (custom.kind == ActionPicker.ADD) {
        sendCustom(custom.id, from);
        return;
      }
    }
    log.add("try", from + ": Spotify offers no save button right now");
  }

  boolean sendCustom(String id, String from) {
    MediaController c = controller;
    if (c == null) {
      log.add("try", from + ": Spotify's player is not available");
      return false;
    }
    for (Custom custom : customs) {
      if (custom.id.equals(id)) {
        log.add("try", from + " pressed Spotify's button \"" + custom.label + "\" [" + custom.id + "]");
        try {
          c.getTransportControls().sendCustomAction(custom.raw, null);
        } catch (RuntimeException e) {
          log.add("try", "pressing it failed: " + e);
          return false;
        }
        scheduleFollowUp();
        return true;
      }
    }
    log.add("try", from + ": Spotify no longer offers [" + id + "]");
    return false;
  }

  void standardShuffle(String from) {
    MediaControllerCompat c = compat;
    if (c == null) {
      log.add("try", from + ": standard shuffle is not available");
      return;
    }
    try {
      int current = c.getShuffleMode();
      int next = (current == PlaybackStateCompat.SHUFFLE_MODE_ALL || current == PlaybackStateCompat.SHUFFLE_MODE_GROUP)
          ? PlaybackStateCompat.SHUFFLE_MODE_NONE
          : PlaybackStateCompat.SHUFFLE_MODE_ALL;
      log.add("try", from + " pressed standard shuffle: " + shuffleName(current) + " -> " + shuffleName(next)
          + (standardShuffleOffered() ? "" : " (Spotify does not list this control)"));
      c.getTransportControls().setShuffleMode(next);
      scheduleFollowUp();
    } catch (RuntimeException e) {
      log.add("try", "standard shuffle failed: " + e);
    }
  }

  void standardRepeat(String from) {
    MediaControllerCompat c = compat;
    if (c == null) {
      log.add("try", from + ": standard repeat is not available");
      return;
    }
    try {
      int current = c.getRepeatMode();
      int next;
      if (current == PlaybackStateCompat.REPEAT_MODE_ALL || current == PlaybackStateCompat.REPEAT_MODE_GROUP) {
        next = PlaybackStateCompat.REPEAT_MODE_ONE;
      } else if (current == PlaybackStateCompat.REPEAT_MODE_ONE) {
        next = PlaybackStateCompat.REPEAT_MODE_NONE;
      } else {
        next = PlaybackStateCompat.REPEAT_MODE_ALL;
      }
      log.add("try", from + " pressed standard repeat: " + repeatName(current) + " -> " + repeatName(next)
          + (standardRepeatOffered() ? "" : " (Spotify does not list this control)"));
      c.getTransportControls().setRepeatMode(next);
      scheduleFollowUp();
    } catch (RuntimeException e) {
      log.add("try", "standard repeat failed: " + e);
    }
  }

  private void scheduleFollowUp() {
    main.removeCallbacks(followUp);
    main.postDelayed(followUp, FOLLOW_UP_MS);
  }

  /** Records what Spotify looks like shortly after a button press, so cause and effect sit together. */
  private final Runnable followUp = new Runnable() {
    @Override
    public void run() {
      readAll();
      log.add("try", "afterwards: " + signatureLine());
    }
  };

  // ---------------------------------------------------------------- text helpers

  private String signatureLine() {
    StringBuilder sb = new StringBuilder();
    sb.append(stateName(state)).append(" | ").append(title.isEmpty() ? "(no title)" : title);
    sb.append(" | shuffle ").append(ButtonStates.shuffleName(shuffleState)).append(", repeat ")
        .append(ButtonStates.repeatName(repeatState)).append(", saved ").append(ButtonStates.savedName(savedState));
    sb.append(" | buttons:");
    if (customs.isEmpty()) {
      sb.append(" none");
    }
    for (Custom custom : customs) {
      sb.append(" [").append(custom.id).append(" \"").append(custom.label).append("\" ").append(custom.iconName)
          .append(']');
    }
    return sb.toString();
  }

  static String stateName(int state) {
    switch (state) {
      case PlaybackState.STATE_NONE:
        return "NONE";
      case PlaybackState.STATE_STOPPED:
        return "STOPPED";
      case PlaybackState.STATE_PAUSED:
        return "PAUSED";
      case PlaybackState.STATE_PLAYING:
        return "PLAYING";
      case PlaybackState.STATE_FAST_FORWARDING:
        return "FAST_FORWARDING";
      case PlaybackState.STATE_REWINDING:
        return "REWINDING";
      case PlaybackState.STATE_BUFFERING:
        return "BUFFERING";
      case PlaybackState.STATE_ERROR:
        return "ERROR";
      case PlaybackState.STATE_CONNECTING:
        return "CONNECTING";
      case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
        return "SKIPPING_TO_PREVIOUS";
      case PlaybackState.STATE_SKIPPING_TO_NEXT:
        return "SKIPPING_TO_NEXT";
      case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
        return "SKIPPING_TO_QUEUE_ITEM";
      default:
        return "STATE_" + state;
    }
  }

  static String shuffleName(int mode) {
    switch (mode) {
      case PlaybackStateCompat.SHUFFLE_MODE_NONE:
        return "off";
      case PlaybackStateCompat.SHUFFLE_MODE_ALL:
        return "on";
      case PlaybackStateCompat.SHUFFLE_MODE_GROUP:
        return "on (group)";
      default:
        return "not reported";
    }
  }

  static String repeatName(int mode) {
    switch (mode) {
      case PlaybackStateCompat.REPEAT_MODE_NONE:
        return "off";
      case PlaybackStateCompat.REPEAT_MODE_ONE:
        return "one song";
      case PlaybackStateCompat.REPEAT_MODE_ALL:
        return "all";
      case PlaybackStateCompat.REPEAT_MODE_GROUP:
        return "group";
      default:
        return "not reported";
    }
  }

  private static final String[] ACTION_NAMES = {"STOP", "PAUSE", "PLAY", "REWIND", "SKIP_TO_PREVIOUS", "SKIP_TO_NEXT",
      "FAST_FORWARD", "SET_RATING", "SEEK_TO", "PLAY_PAUSE", "PLAY_FROM_MEDIA_ID", "PLAY_FROM_SEARCH",
      "SKIP_TO_QUEUE_ITEM", "PLAY_FROM_URI", "PREPARE", "PREPARE_FROM_MEDIA_ID", "PREPARE_FROM_SEARCH",
      "PREPARE_FROM_URI", "SET_REPEAT_MODE", "SET_SHUFFLE_MODE_ENABLED", "SET_CAPTIONING_ENABLED", "SET_SHUFFLE_MODE",
      "SET_PLAYBACK_SPEED"};

  private static String actionNames(long actions) {
    StringBuilder sb = new StringBuilder();
    for (int bit = 0; bit < 63; bit++) {
      if ((actions & (1L << bit)) == 0) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(bit < ACTION_NAMES.length ? ACTION_NAMES[bit] : "BIT_" + bit);
    }
    return sb.length() == 0 ? "none" : sb.toString();
  }

  private static String shortKey(String key) {
    String prefix = "android.media.metadata.";
    return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
  }

  private static String describeField(MediaMetadata md, String key) {
    try {
      switch (key) {
        case MediaMetadata.METADATA_KEY_DURATION:
        case MediaMetadata.METADATA_KEY_YEAR:
        case MediaMetadata.METADATA_KEY_TRACK_NUMBER:
        case MediaMetadata.METADATA_KEY_NUM_TRACKS:
        case MediaMetadata.METADATA_KEY_DISC_NUMBER:
        case MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE:
          return "number " + md.getLong(key);
        case MediaMetadata.METADATA_KEY_ART:
        case MediaMetadata.METADATA_KEY_ALBUM_ART:
        case MediaMetadata.METADATA_KEY_DISPLAY_ICON: {
          Bitmap picture = md.getBitmap(key);
          return picture == null ? "picture (unreadable)" : "picture " + picture.getWidth() + "x" + picture.getHeight();
        }
        case MediaMetadata.METADATA_KEY_USER_RATING:
        case MediaMetadata.METADATA_KEY_RATING: {
          Rating rating = md.getRating(key);
          return "rating " + rating;
        }
        default:
          break;
      }
      CharSequence text = md.getText(key);
      if (text != null) {
        return "text \"" + clip(text.toString(), 80) + "\"";
      }
      Rating rating = md.getRating(key);
      if (rating != null) {
        return "rating " + rating;
      }
      Bitmap picture = md.getBitmap(key);
      if (picture != null) {
        return "picture " + picture.getWidth() + "x" + picture.getHeight();
      }
      return "number " + md.getLong(key);
    } catch (RuntimeException e) {
      return "(unreadable: " + e.getClass().getSimpleName() + ")";
    }
  }

  /** Lists what is inside a bundle without assuming anything about its contents. */
  @SuppressWarnings("deprecation")
  static String describeBundle(Bundle bundle) {
    if (bundle == null) {
      return "-";
    }
    try {
      StringBuilder sb = new StringBuilder("{");
      for (String key : new TreeSet<>(bundle.keySet())) {
        if (sb.length() > 1) {
          sb.append(", ");
        }
        sb.append(key).append('=');
        try {
          Object value = bundle.get(key);
          if (value == null) {
            sb.append("null");
          } else if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            sb.append(clip(value.toString(), 60));
          } else if (value instanceof Bundle) {
            sb.append(describeBundle((Bundle) value));
          } else {
            sb.append('<').append(value.getClass().getSimpleName()).append('>');
          }
        } catch (RuntimeException e) {
          sb.append("<unreadable>");
        }
      }
      return sb.append('}').toString();
    } catch (RuntimeException e) {
      return "<unreadable bundle>";
    }
  }

  private static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
