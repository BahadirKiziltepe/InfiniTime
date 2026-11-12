package io.github.bahadirkiziltepe.watchhelper;

import android.os.Handler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Answers the watch when it asks for a page of the queue, the playlists, a playlist's songs or
 * the people to call, and does what the watch asks to be done with a row.
 *
 * <p>Anything that needs Spotify's web service runs on one background thread, one request after
 * the other. The answers go back to the watch from the main thread, like everything else.
 */
final class WatchLists {
  /** The watch wants a page: list, request number, offset (2 bytes), parent (2 bytes). */
  static final int REQUEST_PAGE = 0x30;
  /** The watch wants something done with a row: list, action, row (2), parent (2), snapshot. */
  static final int REQUEST_ACTION = 0x31;
  static final int ACTION_TAP = 1;
  static final int ACTION_HOLD = 2;

  private static final long SONG_CHECK_DELAY_MS = 1500;
  private static final long SONG_CHECK_AGAIN_MS = 2500;

  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final SpotifyAccount account;
  private final MusicLibrary library;
  private final ContactStore contacts;
  private final CallWatcher calls;
  private final SpotifyWatcher spotify;

  private final ExecutorService io = Executors.newSingleThreadExecutor(work -> {
    Thread thread = new Thread(work, "watch-lists");
    thread.setDaemon(true);
    return thread;
  });
  /** The newest request number per list. An answer to an older request would only be thrown away by the watch. */
  private final AtomicIntegerArray newest = new AtomicIntegerArray(PhoneFrames.LIST_HISTORY + 1);

  // The song the mark for "Spotify picked this" is being worked out for.
  private volatile int songRound;
  private volatile String songTitle = "";
  private volatile int appShuffle = ButtonStates.UNKNOWN;
  private String songSignature = "";

  private volatile int pagesAsked;
  private volatile int pagesSent;
  private volatile int actionsAsked;
  private volatile int actionsDone;
  private volatile int picksFound;

  WatchLists(Handler main, EventLog log, WatchLink watch, SpotifyAccount account, MusicLibrary library,
      ContactStore contacts, CallWatcher calls, SpotifyWatcher spotify) {
    this.main = main;
    this.log = log;
    this.watch = watch;
    this.account = account;
    this.library = library;
    this.contacts = contacts;
    this.calls = calls;
    this.spotify = spotify;
  }

  /** Somebody logged in or out: what is known about the old account is of no use. */
  void onAccountChanged() {
    io.execute(library::reset);
  }

  String dump() {
    return "Pages asked for by the watch: " + pagesAsked + ", sent: " + pagesSent + "\nThings the watch asked to be done: "
        + actionsAsked + ", done: " + actionsDone + "\nSongs marked as Spotify's own pick: " + picksFound
        + "\nPeople on the watch list: " + contacts.all().size();
  }

  // ---------------------------------------------------------------- from the watch

  void onRequest(byte[] m) {
    int code = m[0] & 0xff;
    if (code == REQUEST_PAGE && m.length >= 7) {
      page(m[1] & 0xff, m[2] & 0xff, u16(m, 3), u16(m, 5));
    } else if (code == REQUEST_ACTION && m.length >= 8) {
      action(m[1] & 0xff, m[2] & 0xff, u16(m, 3), u16(m, 5), m[7] & 0xff);
    } else {
      log.add("lists", "the watch sent a request this app does not know (" + code + ", " + m.length + " bytes)");
    }
  }

  private static int u16(byte[] m, int at) {
    return (m[at] & 0xff) | ((m[at + 1] & 0xff) << 8);
  }

  private static boolean known(int kind) {
    return kind >= PhoneFrames.LIST_QUEUE && kind <= PhoneFrames.LIST_HISTORY;
  }

  private void page(final int kind, final int token, final int offset, final int parent) {
    if (!known(kind)) {
      return;
    }
    pagesAsked++;
    newest.set(kind, token);
    if (kind == PhoneFrames.LIST_CONTACTS) {
      send(contactsPage(token, offset));
      return;
    }
    io.execute(() -> {
      if (newest.get(kind) != token) {
        return;
      }
      final List<byte[]> answer = musicPage(kind, token, offset, parent);
      main.post(() -> {
        if (newest.get(kind) == token) {
          send(answer);
        }
      });
    });
  }

  private void send(List<byte[]> page) {
    if (watch.sendPhone(page)) {
      pagesSent++;
    }
  }

  private void action(final int kind, final int action, final int index, final int parent, final int snapshot) {
    if (!known(kind)) {
      return;
    }
    actionsAsked++;
    if (kind == PhoneFrames.LIST_CONTACTS) {
      watch.sendPhone(call(action, index, snapshot));
      return;
    }
    io.execute(() -> {
      final List<byte[]> answer = musicAction(kind, action, index, parent, snapshot);
      main.post(() -> watch.sendPhone(answer));
    });
  }

  // ---------------------------------------------------------------- people

  private List<byte[]> contactsPage(int token, int offset) {
    List<ContactStore.Person> all = contacts.all();
    int start = clamp(offset, all.size());
    List<PhoneFrames.Row> rows = new ArrayList<>();
    for (int i = start; i < all.size() && rows.size() < PhoneFrames.ROWS_PER_PAGE; i++) {
      rows.add(new PhoneFrames.Row(all.get(i).name, 0));
    }
    String note = all.isEmpty() ? "Nobody here yet. Add people in the Watch Helper app on the phone." : "";
    return PhoneFrames.listPage(PhoneFrames.LIST_CONTACTS, token, start, all.size(), contacts.generation(), "", note,
        rows);
  }

  private List<byte[]> call(int action, int index, int snapshot) {
    int kind = PhoneFrames.LIST_CONTACTS;
    List<ContactStore.Person> all = contacts.all();
    if (snapshot != contacts.generation() || index >= all.size()) {
      return PhoneFrames.listResult(kind, PhoneFrames.RESULT_STALE, "");
    }
    if (action != ACTION_TAP) {
      return PhoneFrames.listResult(kind, PhoneFrames.RESULT_FAILED, "");
    }
    ContactStore.Person person = all.get(index);
    String problem = calls.place(person.name, person.number);
    if (problem != null) {
      return PhoneFrames.listResult(kind, PhoneFrames.RESULT_FAILED, problem);
    }
    actionsDone++;
    return PhoneFrames.listResult(kind, PhoneFrames.RESULT_DONE, "Calling");
  }

  // ---------------------------------------------------------------- music (background thread)

  private List<byte[]> musicPage(int kind, int token, int offset, int parent) {
    String title = "";
    try {
      if (kind == PhoneFrames.LIST_PLAYLISTS) {
        // Asking for the first page is opening the list: a good moment to look for changes.
        List<MusicLibrary.Playlist> all = library.playlists(offset == 0);
        int start = clamp(offset, all.size());
        List<PhoneFrames.Row> rows = new ArrayList<>();
        for (int i = start; i < all.size() && rows.size() < PhoneFrames.ROWS_PER_PAGE; i++) {
          MusicLibrary.Playlist p = all.get(i);
          rows.add(new PhoneFrames.Row(p.name, p.mine ? 0 : PhoneFrames.ROW_CLOSED));
        }
        return PhoneFrames.listPage(kind, token, start, all.size(), library.generation(), "", "", rows);
      }

      if (kind == PhoneFrames.LIST_SONGS) {
        List<MusicLibrary.Playlist> all = library.playlists(false);
        if (parent >= all.size()) {
          return PhoneFrames.listPage(kind, token, 0, 0, library.generation(), "",
              "The playlists changed. Go back and open it again.", null);
        }
        MusicLibrary.Playlist playlist = all.get(parent);
        title = playlist.name;
        MusicLibrary.SongPage page = library.songs(playlist, offset, PhoneFrames.ROWS_PER_PAGE);
        int start = offset;
        if (page.songs.isEmpty() && page.total > 0) {
          // Past the end, because the playlist got shorter: show its last page.
          start = clamp(offset, page.total);
          page = library.songs(playlist, start, PhoneFrames.ROWS_PER_PAGE);
        }
        String playing = spotify.title();
        List<PhoneFrames.Row> rows = new ArrayList<>();
        for (MusicLibrary.Song song : page.songs) {
          boolean isPlaying = spotify.isPlaying() && !playing.isEmpty() && playing.equals(song.title);
          rows.add(new PhoneFrames.Row(song.label(), isPlaying ? PhoneFrames.ROW_PLAYING : 0));
        }
        String note = page.total == 0 ? "This playlist is empty." : "";
        return PhoneFrames.listPage(kind, token, start, Math.max(page.total, 0), library.generation(), title, note, rows);
      }

      if (kind == PhoneFrames.LIST_HISTORY) {
        // Asking for the first page is opening the list: look what has been played since.
        List<MusicLibrary.Song> played = library.history(offset == 0);
        int start = clamp(offset, played.size());
        List<PhoneFrames.Row> rows = new ArrayList<>();
        for (int i = start; i < played.size() && rows.size() < PhoneFrames.ROWS_PER_PAGE; i++) {
          rows.add(new PhoneFrames.Row(played.get(i).label(), 0));
        }
        String note = played.isEmpty() ? "Spotify has no played songs to show yet." : "";
        return PhoneFrames.listPage(kind, token, start, played.size(), library.historyGeneration(), "", note, rows);
      }

      List<MusicLibrary.Song> queue = library.queue();
      int start = clamp(offset, queue.size());
      List<MusicLibrary.Song> shown = new ArrayList<>();
      for (int i = start; i < queue.size() && shown.size() < PhoneFrames.ROWS_PER_PAGE; i++) {
        shown.add(queue.get(i));
      }
      Set<String> picks = picksAmong(queue);
      List<PhoneFrames.Row> rows = new ArrayList<>();
      for (MusicLibrary.Song song : shown) {
        rows.add(new PhoneFrames.Row(song.label(), song.uri != null && picks.contains(song.uri) ? PhoneFrames.ROW_SMART : 0));
      }
      String note = queue.isEmpty() ? "Nothing is queued. Start a song or a playlist first." : "";
      return PhoneFrames.listPage(kind, token, start, queue.size(), 0, "", note, rows);
    } catch (MusicLibrary.Problem problem) {
      log.add("lists", "no page for the watch: " + problem.getMessage());
      return PhoneFrames.listPage(kind, token, 0, 0, library.generation(), title, problem.note, null);
    } catch (RuntimeException e) {
      log.add("lists", "a page for the watch failed: " + e);
      return PhoneFrames.listPage(kind, token, 0, 0, library.generation(), title, "Something went wrong on the phone.", null);
    }
  }

  /** Which of the queued songs Spotify picked itself. Empty unless Smart Shuffle is on. */
  private Set<String> picksAmong(List<MusicLibrary.Song> queue) {
    Set<String> none = new HashSet<>();
    int shuffle = appShuffle;
    if (queue.isEmpty() || shuffle == ButtonStates.SHUFFLE_OFF || shuffle == ButtonStates.SHUFFLE_ON) {
      return none;
    }
    try {
      List<String> uris = new ArrayList<>();
      for (MusicLibrary.Song song : queue) {
        if (song.uri != null) {
          uris.add(song.uri);
        }
      }
      return library.picks(uris, library.playing(), shuffle);
    } catch (MusicLibrary.Problem problem) {
      return none;
    }
  }

  private List<byte[]> musicAction(int kind, int action, int index, int parent, int snapshot) {
    try {
      if (kind == PhoneFrames.LIST_HISTORY) {
        List<MusicLibrary.Song> played = library.history(false);
        if (snapshot != library.historyGeneration() || index >= played.size()) {
          return PhoneFrames.listResult(kind, PhoneFrames.RESULT_STALE, "");
        }
        if (action == ACTION_HOLD) {
          library.playAlone(played.get(index));
          log.add("lists", "watch: play a song from the history on its own");
        } else {
          library.addToQueue(played.get(index));
          log.add("lists", "watch: add a song from the history to the queue");
        }
        actionsDone++;
        return PhoneFrames.listResult(kind, PhoneFrames.RESULT_DONE, action == ACTION_HOLD ? "Playing" : "Queued");
      }
      if (snapshot != library.generation()) {
        return PhoneFrames.listResult(kind, PhoneFrames.RESULT_STALE, "");
      }
      List<MusicLibrary.Playlist> all = library.playlists(false);
      String done;
      if (kind == PhoneFrames.LIST_PLAYLISTS && action == ACTION_HOLD && index < all.size()) {
        library.play(all.get(index), -1);
        log.add("lists", "watch: play a playlist");
        done = "Playing";
      } else if (kind == PhoneFrames.LIST_SONGS && parent < all.size()) {
        MusicLibrary.Playlist playlist = all.get(parent);
        if (action == ACTION_HOLD) {
          library.play(playlist, index);
          log.add("lists", "watch: play a song in its playlist");
          done = "Playing";
        } else {
          library.addToQueue(library.songAt(playlist, index));
          log.add("lists", "watch: add a song to the queue");
          done = "Queued";
        }
      } else {
        return PhoneFrames.listResult(kind, PhoneFrames.RESULT_FAILED, "");
      }
      actionsDone++;
      return PhoneFrames.listResult(kind, PhoneFrames.RESULT_DONE, done);
    } catch (MusicLibrary.Problem problem) {
      log.add("lists", "could not do what the watch asked: " + problem.getMessage());
      return PhoneFrames.listResult(kind, PhoneFrames.RESULT_FAILED, problem.shortText);
    } catch (RuntimeException e) {
      log.add("lists", "what the watch asked for failed: " + e);
      return PhoneFrames.listResult(kind, PhoneFrames.RESULT_FAILED, "Phone error");
    }
  }

  /** The start of the page that holds {@code offset}, or of the last page if the list is shorter than that. */
  private static int clamp(int offset, int total) {
    if (total <= 0) {
      return 0;
    }
    int last = (total - 1) / PhoneFrames.ROWS_PER_PAGE * PhoneFrames.ROWS_PER_PAGE;
    return Math.max(0, Math.min(offset / PhoneFrames.ROWS_PER_PAGE * PhoneFrames.ROWS_PER_PAGE, last));
  }

  // ---------------------------------------------------------------- the mark on the song that is playing

  /**
   * Called whenever Spotify's player changed. When the song is a new one, works out whether
   * Spotify picked it itself (Smart Shuffle) and tells the watch, which shows a star for it.
   */
  void onPlayerChanged() {
    appShuffle = spotify.shuffleState();
    String signature = spotify.attached() ? spotify.title() + "\n" + spotify.artist() + "\n" + spotify.durationMs() : "";
    if (signature.equals(songSignature)) {
      return;
    }
    songSignature = signature;
    songTitle = spotify.title();
    final int round = ++songRound;
    // Not known for the new song yet, and the old song's mark must not stay up meanwhile.
    watch.setTrackFlags(0);
    if (signature.isEmpty() || songTitle.isEmpty() || !account.loggedIn()) {
      return;
    }
    main.postDelayed(() -> io.execute(() -> checkSong(round, 0)), SONG_CHECK_DELAY_MS);
  }

  private void checkSong(final int round, final int attempt) {
    int shuffle = appShuffle;
    if (round != songRound || shuffle == ButtonStates.SHUFFLE_OFF || shuffle == ButtonStates.SHUFFLE_ON) {
      return;
    }
    try {
      MusicLibrary.Playing now = library.playing();
      if (now == null || now.songUri == null || now.songTitle == null || !now.songTitle.trim().equalsIgnoreCase(songTitle.trim())) {
        // Spotify's web service can be a moment behind its app. Ask once more.
        if (attempt < 2) {
          main.postDelayed(() -> io.execute(() -> checkSong(round, attempt + 1)), SONG_CHECK_AGAIN_MS);
        }
        return;
      }
      List<String> one = new ArrayList<>();
      one.add(now.songUri);
      final boolean pick = !library.picks(one, now, shuffle).isEmpty();
      if (pick) {
        picksFound++;
      }
      main.post(() -> {
        if (round == songRound) {
          watch.setTrackFlags(pick ? 1 : 0);
        }
      });
    } catch (MusicLibrary.Problem problem) {
      // No mark. The problem is already noted where it happened.
    } catch (RuntimeException e) {
      log.add("lists", "checking the song failed: " + e);
    }
  }
}
