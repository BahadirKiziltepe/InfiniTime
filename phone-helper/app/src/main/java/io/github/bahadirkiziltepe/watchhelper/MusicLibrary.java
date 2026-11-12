package io.github.bahadirkiziltepe.watchhelper;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The playlists, their songs and the play queue, as Spotify's web service hands them out, and the
 * two things the watch can ask for: play a song in its playlist, or add it to the queue.
 *
 * <p>Every method talks to Spotify and waits for the answer, so all of them belong on the one
 * background thread that {@link WatchLists} runs. Nothing here is safe to call from two threads.
 *
 * <p>What Spotify allows an app like this one (as of its February 2026 rules): the list of
 * playlists is complete, but the songs are only listed for playlists the user made or works on
 * with others. A playlist that is merely followed can be played, not opened.
 */
final class MusicLibrary {
  /** Something that did not work, worded for the watch. */
  static final class Problem extends Exception {
    /** A few words for the watch's title line. */
    final String shortText;
    /** A sentence for the place of the rows on the watch. */
    final String note;

    Problem(String shortText, String note, String detail) {
      super(detail);
      this.shortText = shortText;
      this.note = note;
    }
  }

  static final class Playlist {
    final String id;
    final String uri;
    final String name;
    /** The saved songs, which Spotify does not count among the playlists. */
    final boolean liked;
    /** Made by the user or worked on with others: Spotify lists the songs of these only. */
    final boolean mine;
    /** Changes whenever the playlist's contents change. */
    final String version;

    Playlist(String id, String uri, String name, boolean liked, boolean mine, String version) {
      this.id = id;
      this.uri = uri;
      this.name = name;
      this.liked = liked;
      this.mine = mine;
      this.version = version;
    }
  }

  static final class Song {
    /** Null for something that cannot be played from here (removed, or a file on another device). */
    final String uri;
    final String title;
    final String artist;

    Song(String uri, String title, String artist) {
      this.uri = uri;
      this.title = title;
      this.artist = artist;
    }

    /** How the watch shows it. */
    String label() {
      return artist.isEmpty() ? title : title + " - " + artist;
    }
  }

  static final class SongPage {
    final List<Song> songs;
    final int total;

    SongPage(List<Song> songs, int total) {
      this.songs = songs;
      this.total = total;
    }
  }

  /** What Spotify is playing, and from where. */
  static final class Playing {
    /** The playlist, album or the like the song is played from, or null. */
    final String contextUri;
    final String songUri;
    final String songTitle;
    /** Whether Spotify said if Smart Shuffle is on, and what it said. */
    final boolean smartKnown;
    final boolean smartOn;

    Playing(String contextUri, String songUri, String songTitle, boolean smartKnown, boolean smartOn) {
      this.contextUri = contextUri;
      this.songUri = songUri;
      this.songTitle = songTitle;
      this.smartKnown = smartKnown;
      this.smartOn = smartOn;
    }
  }

  private static final String API = "https://api.spotify.com/v1";
  /** Songs fetched per request. A multiple of the watch's page, so a watch page never spans two. */
  private static final int FETCH = 48;
  private static final int MAX_PLAYLISTS = 600;
  /** Past this many songs a playlist is not read to the end to tell Spotify's own picks apart. */
  private static final int MAX_MEMBERS = 3000;
  private static final int KEPT_PLAYLISTS = 6;
  private static final long FRESH_MS = 10 * 60_000;
  private static final long QUEUED_MEMORY_MS = 6 * 60 * 60_000L;
  private static final String LIKED_ID = "liked";

  private static final class Songs {
    final Map<Integer, List<Song>> fetched = new HashMap<>();
    final Map<Integer, Long> fetchedAt = new HashMap<>();
    int total = -1;
    /** Every song of the playlist, once all of it has been read; for telling Spotify's picks apart. */
    Set<String> members;
    String membersVersion;
  }

  private final SpotifyAccount account;
  private final EventLog log;
  /** Starts Spotify on the phone when it is not running, the way a headset's play button does. */
  private final Runnable wakeSpotify;

  private List<Playlist> playlists;
  private long playlistsAt;
  private int generation = 1;
  private final LinkedHashMap<String, Songs> songs = new LinkedHashMap<>(8, 0.75f, true);
  private String myId = "";
  /** Spotify renamed the address of a playlist's songs in 2026. The one that works is remembered. */
  private boolean oldSongsAddress;
  private boolean oldSavedCheck;
  /** The songs played last, as they were when the watch last opened that list, and a name for that state. */
  private List<Song> history = new ArrayList<>();
  private long historyAt = -HISTORY_FRESH_MS;
  private int historyGeneration = 1;
  private static final long HISTORY_FRESH_MS = 20_000;
  private static final int HISTORY_MAX = 40;

  /** Songs the watch added to the queue. They are not in the playlist either, but nobody's "pick". */
  private final Map<String, Long> queuedFromWatch = new HashMap<>();

  // For the screen and the report. Written on the background thread, read on the main one.
  private volatile int requests;
  private volatile int lastCode;
  private volatile int playlistCount = -1;
  private volatile String lastProblem = "";

  MusicLibrary(SpotifyAccount account, EventLog log, Runnable wakeSpotify) {
    this.account = account;
    this.log = log;
    this.wakeSpotify = wakeSpotify;
  }

  /** Forgets everything, for when somebody else logs in or nobody is logged in any more. */
  void reset() {
    playlists = null;
    songs.clear();
    myId = "";
    queuedFromWatch.clear();
    history = new ArrayList<>();
    historyAt = -HISTORY_FRESH_MS;
    historyGeneration = historyGeneration % 250 + 1;
    generation = generation % 250 + 1;
    playlistCount = -1;
    lastProblem = "";
  }

  /** Names the list of playlists as it is now. It changes when the list does. */
  int generation() {
    return generation;
  }

  int playlistCount() {
    return playlistCount;
  }

  String lastProblem() {
    return lastProblem;
  }

  String dump() {
    return "Requests to Spotify's web service: " + requests + ", last answer: " + (lastCode == 0 ? "none" : lastCode)
        + "\nPlaylists known: " + (playlistCount < 0 ? "not asked yet" : String.valueOf(playlistCount))
        + (lastProblem.isEmpty() ? "" : "\nLast problem: " + lastProblem);
  }

  // ---------------------------------------------------------------- playlists

  /**
   * The saved songs first, then the playlists in Spotify's order. {@code mayRefresh} allows asking
   * Spotify again when the list is not fresh any more; without it a known list is used as it is,
   * so that row numbers the watch holds keep meaning the same playlists.
   */
  List<Playlist> playlists(boolean mayRefresh) throws Problem {
    long now = SystemClock.elapsedRealtime();
    if (playlists != null && (!mayRefresh || now - playlistsAt < FRESH_MS)) {
      return playlists;
    }
    List<Playlist> found = new ArrayList<>();
    found.add(new Playlist(LIKED_ID, null, "Liked Songs", true, true, ""));
    try {
      String me = myId();
      for (int offset = 0; offset < MAX_PLAYLISTS;) {
        JSONObject json = parse(need(call("GET", "/me/playlists?limit=50&offset=" + offset, null)));
        JSONArray items = json.optJSONArray("items");
        int count = items == null ? 0 : items.length();
        for (int i = 0; i < count; i++) {
          JSONObject p = items.optJSONObject(i);
          String id = p == null ? "" : p.optString("id", "");
          if (id.isEmpty()) {
            continue;
          }
          JSONObject owner = p.optJSONObject("owner");
          String ownerId = owner == null ? "" : owner.optString("id", "");
          boolean mine = me.isEmpty() || me.equals(ownerId) || p.optBoolean("collaborative", false);
          String name = p.isNull("name") ? "" : p.optString("name", "");
          found.add(new Playlist(id, "spotify:playlist:" + id, name.isEmpty() ? "(no name)" : name, false, mine,
              p.optString("snapshot_id", "")));
        }
        offset += count;
        if (count == 0 || json.isNull("next")) {
          break;
        }
      }
    } catch (Problem problem) {
      if (playlists != null) {
        // Better the list from before than none.
        log.add("lists", "could not refresh the playlists (" + problem.getMessage() + "); using the earlier list");
        playlistsAt = now;
        return playlists;
      }
      throw problem;
    }
    if (!sameOrder(found, playlists)) {
      generation = generation % 250 + 1;
    }
    playlists = found;
    playlistsAt = now;
    playlistCount = found.size() - 1;
    return playlists;
  }

  private static boolean sameOrder(List<Playlist> a, List<Playlist> b) {
    if (a == null || b == null || a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!a.get(i).id.equals(b.get(i).id)) {
        return false;
      }
    }
    return true;
  }

  private Playlist byUri(String uri) throws Problem {
    // Asking again now and then keeps the playlists' versions current, which is how a change to
    // a playlist's songs is noticed.
    for (Playlist p : playlists(true)) {
      if (uri.equals(p.uri)) {
        return p;
      }
    }
    return null;
  }

  private String myId() {
    if (myId.isEmpty()) {
      myId = account.userId();
    }
    if (myId.isEmpty()) {
      try {
        Http.Reply reply = call("GET", "/me", null);
        if (reply.ok()) {
          myId = parse(reply.body).optString("id", "");
        }
      } catch (Problem e) {
        // Stays unknown; every playlist is then treated as the user's own until Spotify says otherwise.
      }
    }
    return myId;
  }

  // ---------------------------------------------------------------- songs

  /** The songs of a playlist from {@code offset} on, at most {@code count} of them. */
  SongPage songs(Playlist playlist, int offset, int count) throws Problem {
    Songs known = songsOf(playlist);
    List<Song> part = fetch(playlist, known, offset / FETCH);
    int from = offset % FETCH;
    int to = Math.min(part.size(), from + count);
    return new SongPage(from < to ? new ArrayList<>(part.subList(from, to)) : new ArrayList<Song>(), known.total);
  }

  /** One song by its place in the playlist, or null if there is none there. */
  Song songAt(Playlist playlist, int index) throws Problem {
    List<Song> part = fetch(playlist, songsOf(playlist), index / FETCH);
    return index % FETCH < part.size() ? part.get(index % FETCH) : null;
  }

  private Songs songsOf(Playlist playlist) {
    Songs known = songs.get(playlist.id);
    if (known == null) {
      known = new Songs();
      songs.put(playlist.id, known);
      while (songs.size() > KEPT_PLAYLISTS) {
        songs.remove(songs.keySet().iterator().next());
      }
    }
    return known;
  }

  private List<Song> fetch(Playlist playlist, Songs known, int part) throws Problem {
    long now = SystemClock.elapsedRealtime();
    List<Song> have = known.fetched.get(part);
    Long at = known.fetchedAt.get(part);
    if (have != null && at != null && now - at < FRESH_MS) {
      return have;
    }
    String paging = "?limit=" + FETCH + "&offset=" + (part * FETCH);
    Http.Reply reply;
    if (playlist.liked) {
      reply = call("GET", "/me/tracks" + paging, null);
    } else {
      reply = call("GET", "/playlists/" + playlist.id + (oldSongsAddress ? "/tracks" : "/items") + paging, null);
      if (reply.code == 404 && !oldSongsAddress) {
        Http.Reply older = call("GET", "/playlists/" + playlist.id + "/tracks" + paging, null);
        if (older.ok()) {
          oldSongsAddress = true;
          reply = older;
        }
      }
      if (reply.code == 403 || reply.code == 404) {
        throw closed();
      }
    }
    JSONObject json = parse(need(reply));
    JSONArray items = json.optJSONArray("items");
    if (items == null) {
      if (playlist.liked) {
        throw new Problem("Spotify error", "Spotify sent no songs. Try again.", "no items in the answer");
      }
      throw closed();
    }
    List<Song> list = new ArrayList<>(items.length());
    for (int i = 0; i < items.length(); i++) {
      JSONObject entry = items.optJSONObject(i);
      JSONObject track = entry == null ? null : entry.optJSONObject("item");
      if (track == null && entry != null) {
        track = entry.optJSONObject("track");
      }
      list.add(songOf(track));
    }
    known.total = json.optInt("total", part * FETCH + list.size());
    known.fetched.put(part, list);
    known.fetchedAt.put(part, now);
    return list;
  }

  private static Problem closed() {
    return new Problem("Hold to play it", "Spotify only lists songs of your own playlists. Go back, hold to play.",
        "Spotify does not list this playlist's songs");
  }

  private static Song songOf(JSONObject track) {
    if (track == null) {
      return new Song(null, "(not available)", "");
    }
    String uri = track.optString("uri", "");
    String title = track.isNull("name") ? "" : track.optString("name", "");
    StringBuilder artist = new StringBuilder();
    JSONArray artists = track.optJSONArray("artists");
    for (int i = 0; artists != null && i < artists.length() && i < 2; i++) {
      JSONObject a = artists.optJSONObject(i);
      String name = a == null || a.isNull("name") ? "" : a.optString("name", "");
      if (!name.isEmpty()) {
        artist.append(artist.length() > 0 ? ", " : "").append(name);
      }
    }
    if (artist.length() == 0) {
      // A podcast episode names its show instead.
      JSONObject show = track.optJSONObject("show");
      if (show != null && !show.isNull("name")) {
        artist.append(show.optString("name", ""));
      }
    }
    boolean playable = !uri.isEmpty() && !uri.startsWith("spotify:local:");
    return new Song(playable ? uri : null, title.isEmpty() ? "(no title)" : title, artist.toString());
  }

  // ---------------------------------------------------------------- what is playing, and the queue

  /** What Spotify is playing right now, or null when nothing is. */
  Playing playing() throws Problem {
    Http.Reply reply = call("GET", "/me/player?additional_types=episode", null);
    if (reply.code == 204 || (reply.ok() && reply.body.trim().isEmpty())) {
      return null;
    }
    JSONObject json = parse(need(reply));
    JSONObject context = json.optJSONObject("context");
    JSONObject item = json.optJSONObject("item");
    String contextUri = context == null ? null : context.optString("uri", null);
    return new Playing(contextUri == null || contextUri.isEmpty() ? null : contextUri,
        item == null ? null : item.optString("uri", null),
        item == null || item.isNull("name") ? null : item.optString("name", null),
        json.has("smart_shuffle") && !json.isNull("smart_shuffle"), json.optBoolean("smart_shuffle", false));
  }

  /**
   * The songs that were played last, newest first, each once, at most forty. {@code mayRefresh}
   * allows asking Spotify again; without it the list from before is used, so that the row numbers
   * the watch holds keep meaning the same songs.
   */
  List<Song> history(boolean mayRefresh) throws Problem {
    long now = SystemClock.elapsedRealtime();
    if (!mayRefresh || now - historyAt < HISTORY_FRESH_MS) {
      return history;
    }
    Http.Reply reply = call("GET", "/me/player/recently-played?limit=50", null);
    if (reply.code == 403) {
      throw fail(new Problem("Log in again", "Log in to Spotify again in the phone app to allow the history.",
          "Spotify answered 403 (" + SpotifyAccount.errorText(reply.body) + "): the login does not cover the history"));
    }
    JSONArray items = parse(need(reply)).optJSONArray("items");
    List<Song> found = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; items != null && i < items.length() && found.size() < HISTORY_MAX; i++) {
      JSONObject entry = items.optJSONObject(i);
      Song song = songOf(entry == null ? null : entry.optJSONObject("track"));
      if (song.uri != null && seen.add(song.uri)) {
        found.add(song);
      }
    }
    boolean same = found.size() == history.size();
    for (int i = 0; same && i < found.size(); i++) {
      same = found.get(i).uri.equals(history.get(i).uri);
    }
    if (!same) {
      historyGeneration = historyGeneration % 250 + 1;
    }
    history = found;
    historyAt = now;
    return history;
  }

  /** Names the list of played songs as it is now. It changes when the list does. */
  int historyGeneration() {
    return historyGeneration;
  }

  /** Plays one song on its own. When it ends Spotify carries on by itself with songs like it. */
  void playAlone(Song song) throws Problem {
    if (song == null || song.uri == null) {
      throw new Problem("Can't play this", "", "nothing playable at that place");
    }
    try {
      player("PUT", "/me/player/play", new JSONObject().put("uris", new JSONArray().put(song.uri)).toString());
    } catch (JSONException e) {
      throw new Problem("Did not work", "", "could not build the request: " + e);
    }
  }

  /** The songs that come next, in order. Empty when nothing is playing. */
  List<Song> queue() throws Problem {
    Http.Reply reply = call("GET", "/me/player/queue", null);
    if (reply.code == 204 || (reply.ok() && reply.body.trim().isEmpty())) {
      return new ArrayList<>();
    }
    JSONArray queue = parse(need(reply)).optJSONArray("queue");
    List<Song> out = new ArrayList<>();
    for (int i = 0; queue != null && i < queue.length(); i++) {
      out.add(songOf(queue.optJSONObject(i)));
    }
    return out;
  }

  /**
   * Which of these songs Spotify picked itself: Smart Shuffle is on and they are not part of the
   * playlist that is playing. {@code appShuffle} is what Spotify's app shows on its shuffle button
   * (a ButtonStates value), used when the web service does not say whether Smart Shuffle is on.
   * Never fails: what cannot be found out is simply not marked.
   */
  Set<String> picks(List<String> songUris, Playing now, int appShuffle) {
    Set<String> picks = new HashSet<>();
    if (now == null || now.contextUri == null || songUris.isEmpty()) {
      return picks;
    }
    boolean smart = now.smartKnown ? now.smartOn
        : appShuffle == ButtonStates.SHUFFLE_SMART || appShuffle == ButtonStates.UNKNOWN;
    if (!smart) {
      return picks;
    }
    forgetOldQueued();
    try {
      if (now.contextUri.endsWith(":collection") || now.contextUri.endsWith(":collection:tracks")) {
        List<String> asked = new ArrayList<>();
        for (String uri : songUris) {
          if (uri != null && uri.startsWith("spotify:track:") && !queuedFromWatch.containsKey(uri) && asked.size() < 40) {
            asked.add(uri);
          }
        }
        boolean[] saved = saved(asked);
        for (int i = 0; saved != null && i < asked.size() && i < saved.length; i++) {
          if (!saved[i]) {
            picks.add(asked.get(i));
          }
        }
      } else if (now.contextUri.startsWith("spotify:playlist:")) {
        Playlist playlist = byUri(now.contextUri);
        Set<String> members = playlist == null || !playlist.mine ? null : members(playlist);
        if (members != null) {
          for (String uri : songUris) {
            if (uri != null && !members.contains(uri) && !queuedFromWatch.containsKey(uri)) {
              picks.add(uri);
            }
          }
        }
      }
    } catch (Problem problem) {
      log.add("lists", "could not tell Spotify's own picks apart: " + problem.getMessage());
    }
    return picks;
  }

  /** Every song of a playlist, or null when it is too long to read to the end. */
  private Set<String> members(Playlist playlist) throws Problem {
    Songs known = songsOf(playlist);
    if (known.members != null && playlist.version.equals(known.membersVersion)) {
      return known.members;
    }
    Set<String> all = new HashSet<>();
    for (int part = 0;; part++) {
      List<Song> list = fetch(playlist, known, part);
      for (Song song : list) {
        if (song.uri != null) {
          all.add(song.uri);
        }
      }
      if (known.total > MAX_MEMBERS) {
        return null;
      }
      if (list.isEmpty() || (part + 1) * FETCH >= known.total) {
        break;
      }
      if (part >= 4) {
        // A long playlist: do not hammer Spotify.
        try {
          Thread.sleep(120);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
    known.members = all;
    known.membersVersion = playlist.version;
    return all;
  }

  /** For each song whether it is among the saved ones, or null if Spotify would not say. */
  private boolean[] saved(List<String> uris) throws Problem {
    if (uris.isEmpty()) {
      return new boolean[0];
    }
    Http.Reply reply = null;
    if (!oldSavedCheck) {
      reply = call("GET", "/me/library/contains?uris=" + SpotifyAccount.encode(join(uris, false)), null);
      if (!reply.ok() && reply.code != 401 && reply.code != 429) {
        oldSavedCheck = true;
      }
    }
    if (oldSavedCheck) {
      reply = call("GET", "/me/tracks/contains?ids=" + SpotifyAccount.encode(join(uris, true)), null);
    }
    if (reply == null || !reply.ok()) {
      return null;
    }
    try {
      JSONArray answers = new JSONArray(reply.body);
      boolean[] out = new boolean[answers.length()];
      for (int i = 0; i < out.length; i++) {
        out[i] = answers.optBoolean(i, true);
      }
      return out;
    } catch (JSONException e) {
      return null;
    }
  }

  private static String join(List<String> uris, boolean idsOnly) {
    StringBuilder sb = new StringBuilder();
    for (String uri : uris) {
      sb.append(sb.length() > 0 ? "," : "").append(idsOnly ? uri.substring(uri.lastIndexOf(':') + 1) : uri);
    }
    return sb.toString();
  }

  private void forgetOldQueued() {
    long now = SystemClock.elapsedRealtime();
    queuedFromWatch.values().removeIf(at -> now - at > QUEUED_MEMORY_MS);
  }

  // ---------------------------------------------------------------- playing and queueing

  /** Plays a playlist, from the song at {@code index} if that is not negative. */
  void play(Playlist playlist, int index) throws Problem {
    Song song = index < 0 ? null : songAt(playlist, index);
    if (index >= 0 && (song == null || song.uri == null)) {
      throw new Problem("Can't play this", "", "nothing playable at that place");
    }
    try {
      JSONObject body = new JSONObject();
      if (!playlist.liked) {
        body.put("context_uri", playlist.uri);
        if (song != null) {
          body.put("offset", new JSONObject().put("uri", song.uri));
        }
        player("PUT", "/me/player/play", body.toString());
        return;
      }
      // The saved songs have no address of their own in Spotify's documents. The one below is what
      // Spotify's own apps use; should it be refused, the songs are sent as a plain list instead.
      String me = myId();
      if (!me.isEmpty()) {
        body.put("context_uri", "spotify:user:" + me + ":collection");
        if (song != null) {
          body.put("offset", new JSONObject().put("uri", song.uri));
        }
        try {
          player("PUT", "/me/player/play", body.toString());
          return;
        } catch (Problem refused) {
          log.add("lists", "playing the saved songs as a whole was refused (" + refused.getMessage()
              + "); sending them as a list");
        }
      }
      int first = Math.max(0, index);
      List<Song> part = fetch(playlist, songsOf(playlist), first / FETCH);
      JSONArray uris = new JSONArray();
      for (int i = first % FETCH; i < part.size(); i++) {
        if (part.get(i).uri != null) {
          uris.put(part.get(i).uri);
        }
      }
      if (uris.length() == 0) {
        throw new Problem("Nothing to play", "", "no saved songs");
      }
      player("PUT", "/me/player/play", new JSONObject().put("uris", uris).toString());
    } catch (JSONException e) {
      throw new Problem("Did not work", "", "could not build the request: " + e);
    }
  }

  /** Adds a song to the end of the queue. The playlist that is playing stays as it is. */
  void addToQueue(Song song) throws Problem {
    if (song == null || song.uri == null) {
      throw new Problem("Can't queue this", "", "nothing playable at that place");
    }
    player("POST", "/me/player/queue?uri=" + SpotifyAccount.encode(song.uri), null);
    queuedFromWatch.put(song.uri, SystemClock.elapsedRealtime());
  }

  /** A command for Spotify's player. When no player is active, the phone's is picked and told. */
  private void player(String method, String path, String body) throws Problem {
    Http.Reply reply = call(method, path, body);
    if (reply.code == 404) {
      String device = phonePlayer();
      if (device == null) {
        throw new Problem("Open Spotify", "Spotify is not running on the phone. Open it once and try again.",
            "no Spotify player to send the command to");
      }
      reply = call(method, path + (path.contains("?") ? "&" : "?") + "device_id=" + SpotifyAccount.encode(device), body);
    }
    need(reply);
  }

  /** The id of the player to use when none is active: an active one if any, else a phone, else whatever there is. */
  private String phonePlayer() throws Problem {
    for (int attempt = 0; attempt < 2; attempt++) {
      JSONArray devices = parse(need(call("GET", "/me/player/devices", null))).optJSONArray("devices");
      String best = null;
      int bestScore = -1;
      for (int i = 0; devices != null && i < devices.length(); i++) {
        JSONObject device = devices.optJSONObject(i);
        String id = device == null || device.isNull("id") ? "" : device.optString("id", "");
        if (id.isEmpty() || device.optBoolean("is_restricted", false)) {
          continue;
        }
        int score = (device.optBoolean("is_active", false) ? 2 : 0)
            + ("smartphone".equalsIgnoreCase(device.optString("type", "")) ? 1 : 0);
        if (score > bestScore) {
          bestScore = score;
          best = id;
        }
      }
      if (best != null) {
        return best;
      }
      if (attempt == 0) {
        log.add("lists", "Spotify is not running on any device; starting it on the phone");
        wakeSpotify.run();
        try {
          Thread.sleep(3000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- talking to Spotify

  private Http.Reply call(String method, String path, String jsonBody) throws Problem {
    Http.Reply reply = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      String token;
      try {
        token = account.token();
      } catch (IOException e) {
        throw offline(e);
      }
      if (token == null) {
        throw fail(new Problem("Log in on phone", "Log in to Spotify in the Watch Helper app on the phone.",
            "not logged in to Spotify's web service"));
      }
      try {
        reply = Http.send(method, API + path, token, jsonBody == null ? null : "application/json",
            jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw offline(e);
      }
      requests++;
      lastCode = reply.code;
      if (reply.code != 401) {
        break;
      }
      // The token was not accepted. Get a new one and ask once more.
      account.tokenRejected();
    }
    return reply;
  }

  /** The answer's text if the request worked; otherwise what went wrong, worded for the watch. */
  private String need(Http.Reply reply) throws Problem {
    if (reply.ok()) {
      lastProblem = "";
      return reply.body;
    }
    String said = SpotifyAccount.errorText(reply.body);
    String detail = "Spotify answered " + reply.code + " (" + said + ")";
    switch (reply.code) {
      case 401:
        throw fail(new Problem("Log in on phone", "Spotify did not accept the login. Log in again in the phone app.",
            detail));
      case 403:
        if (said.toUpperCase(java.util.Locale.ROOT).contains("PREMIUM")) {
          throw fail(new Problem("Needs Premium", "Spotify only does this for Premium accounts.", detail));
        }
        throw fail(new Problem("Spotify said no", "Spotify refused: " + said, detail));
      case 404:
        throw fail(new Problem("Not found", "Spotify could not find that. Go back and open it again.", detail));
      case 429:
        throw fail(new Problem("Spotify is busy", "Spotify asks to wait a little. Try again in a minute.", detail));
      default:
        if (reply.code >= 500) {
          throw fail(new Problem("Spotify error", "Spotify has a problem right now. Try again in a moment.", detail));
        }
        throw fail(new Problem("Spotify said no", "Spotify refused: " + said, detail));
    }
  }

  private Problem offline(IOException e) {
    return fail(new Problem("No internet", "The phone could not reach Spotify. Check its internet.",
        "no connection to Spotify: " + e));
  }

  private Problem fail(Problem problem) {
    lastProblem = problem.getMessage();
    return problem;
  }

  private static JSONObject parse(String body) throws Problem {
    try {
      return new JSONObject(body);
    } catch (JSONException e) {
      throw new Problem("Spotify error", "Spotify's answer could not be read. Try again.", "unreadable answer");
    }
  }
}
