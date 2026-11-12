package io.github.bahadirkiziltepe.watchhelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * The login to Spotify's web service, which is the only place the playlists and the queue can be
 * had from. (Spotify's app on the phone only tells other apps what is playing.)
 *
 * <p>Spotify hands out access per "app" registered on its developer site. This phone app is used
 * by one person, so that person registers it once and types the resulting Client ID in here; there
 * is no secret to keep, the login uses the method Spotify recommends for phone apps (PKCE).
 *
 * <p>Logging in happens in the browser. Spotify then sends the browser to an address on this very
 * phone ({@link #REDIRECT_URI}), where this class listens for a few minutes, picks up the one-time
 * code and trades it for the tokens. The tokens stay in the app's private storage.
 */
final class SpotifyAccount {
  /** Has to be entered exactly like this on Spotify's developer site. */
  static final String REDIRECT_URI = "http://127.0.0.1:21987/callback";
  static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";

  private static final int PORT = 21987;
  private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
  private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
  private static final String ME_URL = "https://api.spotify.com/v1/me";
  /** See the playlists and saved songs, see what is playing and what is queued, and start songs. */
  private static final String SCOPES = "playlist-read-private playlist-read-collaborative user-library-read "
      + "user-read-playback-state user-read-currently-playing user-modify-playback-state";
  private static final long LOGIN_WAIT_MS = 5 * 60_000;
  private static final long EARLY_MS = 60_000;
  private static final String PREFS = "spotify-account";
  private static final String FORM = "application/x-www-form-urlencoded";
  private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

  interface Listener {
    void onAccountChanged();
  }

  private final Handler main;
  private final EventLog log;
  private final Listener listener;
  private final SharedPreferences prefs;
  private final SecureRandom random = new SecureRandom();
  /** Only one thread renews the token at a time; the others wait for it and use its result. */
  private final Object renewLock = new Object();

  private String clientId;
  private String accessToken;
  private String refreshToken;
  private long expiresAt;
  private String userId;
  private String userName;

  private boolean loggingIn;
  private int loginRound;
  private ServerSocket server;
  private String problem = "";

  SpotifyAccount(Context app, Handler main, EventLog log, Listener listener) {
    this.main = main;
    this.log = log;
    this.listener = listener;
    this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    clientId = prefs.getString("client_id", "");
    accessToken = prefs.getString("access", null);
    refreshToken = prefs.getString("refresh", null);
    expiresAt = prefs.getLong("expires", 0);
    userId = prefs.getString("user_id", "");
    userName = prefs.getString("user_name", "");
  }

  // ---------------------------------------------------------------- state for the screen

  synchronized String clientId() {
    return clientId;
  }

  synchronized boolean loggedIn() {
    return refreshToken != null;
  }

  synchronized boolean loggingIn() {
    return loggingIn;
  }

  /** Spotify's name for the account, used to tell the wearer's own playlists from followed ones. */
  synchronized String userId() {
    return userId;
  }

  synchronized String userName() {
    return userName;
  }

  /** Why the last login did not work, or empty. */
  synchronized String problem() {
    return problem;
  }

  /** Remembers the Client ID. A different one means a different registration, so the login goes. */
  void setClientId(String id) {
    String clean = id == null ? "" : id.trim();
    synchronized (this) {
      if (clean.equals(clientId)) {
        return;
      }
      clientId = clean;
      forget();
      problem = "";
    }
    stopWaiting();
    save();
    changed();
  }

  void logout() {
    synchronized (this) {
      forget();
      problem = "";
    }
    stopWaiting();
    save();
    log.add("spotify", "logged out of Spotify's web service");
    changed();
  }

  /** For the report: what is set up, never the tokens or the name. */
  synchronized String dump() {
    return "Client ID entered: " + (clientId.isEmpty() ? "no" : "yes (" + clientId.length() + " characters)")
        + "\nLogged in: " + (refreshToken != null ? "yes" : "no")
        + (refreshToken != null ? ", token good for another " + Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000) + " s" : "")
        + (problem.isEmpty() ? "" : "\nLast problem: " + problem);
  }

  // ---------------------------------------------------------------- logging in

  /** Opens Spotify's login page in the browser and waits for its answer. False if that could not start. */
  boolean startLogin(Activity from) {
    final String id;
    final int round;
    synchronized (this) {
      if (clientId.isEmpty()) {
        problem = "Enter the Client ID first.";
        changed();
        return false;
      }
      id = clientId;
      round = ++loginRound;
      loggingIn = true;
      problem = "";
    }
    closeServer();

    final String verifier = randomText(64);
    final String state = randomText(24);
    // The waiting, and already the opening of the socket, happen away from the main thread:
    // Android does not allow network work there.
    Thread waiter = new Thread(() -> listen(round, id, verifier, state), "spotify-login");
    waiter.setDaemon(true);
    waiter.start();

    String address = AUTHORIZE_URL + "?client_id=" + encode(id) + "&response_type=code&redirect_uri="
        + encode(REDIRECT_URI) + "&code_challenge_method=S256&code_challenge=" + challenge(verifier) + "&scope="
        + encode(SCOPES) + "&state=" + state;
    try {
      from.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
    } catch (RuntimeException e) {
      stopWaiting();
      synchronized (this) {
        problem = "Could not open the browser.";
      }
      changed();
      return false;
    }
    log.add("spotify", "login: the browser is open, waiting for Spotify's answer");
    changed();
    return true;
  }

  private void stopWaiting() {
    synchronized (this) {
      loginRound++;
      loggingIn = false;
    }
    closeServer();
  }

  private void closeServer() {
    ServerSocket old;
    synchronized (this) {
      old = server;
      server = null;
    }
    if (old != null) {
      try {
        old.close();
      } catch (IOException e) {
        // It is gone either way.
      }
    }
  }

  /** Opens the address Spotify sends the browser back to, and waits there for the answer. */
  private void listen(int round, String id, String verifier, String state) {
    ServerSocket socket = null;
    IOException problem = null;
    // The socket of an earlier try may need a moment to let go of the address.
    for (int attempt = 0; attempt < 5 && socket == null; attempt++) {
      try {
        ServerSocket fresh = new ServerSocket();
        fresh.setReuseAddress(true);
        fresh.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), PORT), 4);
        socket = fresh;
      } catch (IOException e) {
        problem = e;
        try {
          Thread.sleep(200);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    if (socket == null) {
      fail(round, "Could not wait for Spotify's answer on this phone (" + (problem == null ? "stopped" : problem.getMessage())
          + "). Tap Log in to try again.");
      return;
    }
    boolean current;
    synchronized (this) {
      current = round == loginRound;
      if (current) {
        server = socket;
      }
    }
    if (!current) {
      // Another login was started, or this one was stopped, in the meantime.
      try {
        socket.close();
      } catch (IOException e) {
        // Nothing to do.
      }
      return;
    }
    waitForAnswer(socket, round, id, verifier, state);
  }

  private void waitForAnswer(ServerSocket socket, int round, String id, String verifier, String state) {
    long deadline = SystemClock.elapsedRealtime() + LOGIN_WAIT_MS;
    String code = null;
    String error = null;
    try {
      while (code == null && error == null) {
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) {
          error = "Nobody logged in within five minutes. Tap Log in to try again.";
          break;
        }
        socket.setSoTimeout((int) Math.min(left, 30_000));
        Socket visitor;
        try {
          visitor = socket.accept();
        } catch (SocketTimeoutException e) {
          continue;
        }
        try (Socket client = visitor) {
          client.setSoTimeout(5_000);
          String target = readTarget(client.getInputStream());
          if (target == null || !target.startsWith("/callback")) {
            respond(client, "404 Not Found", page("Nothing here", ""));
            continue;
          }
          Map<String, String> query = query(target);
          if (!state.equals(query.get("state"))) {
            respond(client, "400 Bad Request",
                page("This is not the login Watch Helper started", "Go back to the app and tap Log in again."));
          } else if (query.get("error") != null) {
            error = "Spotify did not give access (" + query.get("error") + ").";
            respond(client, "200 OK", page("Spotify did not give access", "Go back to Watch Helper and try again."));
          } else if (query.get("code") != null) {
            code = query.get("code");
            respond(client, "200 OK", page("Watch Helper is connected to Spotify", "You can go back to the app now."));
          } else {
            respond(client, "400 Bad Request", page("Something is missing", "Go back to the app and tap Log in again."));
          }
        } catch (IOException e) {
          // A visit that broke off. The next one may be the real one.
        }
      }
    } catch (IOException e) {
      // The socket was closed: the login was cancelled or started again.
      error = "The login was stopped.";
    } finally {
      try {
        socket.close();
      } catch (IOException e) {
        // Nothing to do.
      }
    }
    if (code == null) {
      fail(round, error);
      return;
    }
    trade(round, id, code, verifier);
  }

  /** Trades the one-time code for the tokens and asks Spotify who logged in. */
  private void trade(int round, String id, String code, String verifier) {
    try {
      String form = "grant_type=authorization_code&code=" + encode(code) + "&redirect_uri=" + encode(REDIRECT_URI)
          + "&client_id=" + encode(id) + "&code_verifier=" + encode(verifier);
      Http.Reply reply = Http.send("POST", TOKEN_URL, null, FORM, form.getBytes(StandardCharsets.UTF_8));
      if (!reply.ok()) {
        fail(round, "Spotify refused the login (" + reply.code + ": " + errorText(reply.body) + ").");
        return;
      }
      JSONObject json = new JSONObject(reply.body);
      String access = json.getString("access_token");
      String refresh = json.optString("refresh_token", "");
      long expires = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000;
      if (refresh.isEmpty()) {
        fail(round, "Spotify's answer was incomplete. Try again.");
        return;
      }

      String name = "";
      String user = "";
      Http.Reply me = Http.send("GET", ME_URL, access, null, null);
      if (me.ok()) {
        JSONObject who = new JSONObject(me.body);
        user = who.optString("id", "");
        name = who.isNull("display_name") ? "" : who.optString("display_name", "");
      } else if (me.code == 403) {
        // The login itself worked, so say what Spotify says about using the web service with it.
        fail(round, "Spotify does not let this account use its web service (" + errorText(me.body)
            + "). The account that made the Client ID needs Premium, and other accounts have to be added "
            + "to the app on Spotify's developer site.");
        return;
      }

      synchronized (this) {
        if (round != loginRound) {
          return;
        }
        accessToken = access;
        refreshToken = refresh;
        expiresAt = expires;
        userId = user;
        userName = name.isEmpty() ? user : name;
        loggingIn = false;
        problem = "";
      }
      save();
      log.add("spotify", "logged in to Spotify's web service");
      changed();
    } catch (IOException e) {
      fail(round, "No internet while finishing the login (" + e.getMessage() + ").");
    } catch (JSONException e) {
      fail(round, "Spotify's answer could not be read. Try again.");
    }
  }

  private void fail(int round, String why) {
    synchronized (this) {
      if (round != loginRound) {
        return;
      }
      loggingIn = false;
      problem = why == null ? "" : why;
    }
    log.add("spotify", "login did not finish: " + why);
    changed();
  }

  // ---------------------------------------------------------------- tokens

  /**
   * An access token that is good right now, renewed first if it is about to run out. Null when
   * nobody is logged in. Talks to Spotify, so it must not be called on the main thread.
   */
  String token() throws IOException {
    synchronized (renewLock) {
      final String refresh;
      final String id;
      synchronized (this) {
        if (refreshToken == null) {
          return null;
        }
        if (accessToken != null && System.currentTimeMillis() < expiresAt - EARLY_MS) {
          return accessToken;
        }
        refresh = refreshToken;
        id = clientId;
      }
      String form = "grant_type=refresh_token&refresh_token=" + encode(refresh) + "&client_id=" + encode(id);
      Http.Reply reply = Http.send("POST", TOKEN_URL, null, FORM, form.getBytes(StandardCharsets.UTF_8));
      if (reply.code == 400 || reply.code == 401) {
        // Spotify has withdrawn the login (the password changed, or access was removed).
        synchronized (this) {
          forget();
          problem = "Spotify wants you to log in again (" + errorText(reply.body) + ").";
        }
        save();
        log.add("spotify", "the login is no longer valid: " + reply.code + " " + errorText(reply.body));
        changed();
        return null;
      }
      if (!reply.ok()) {
        throw new IOException("Spotify's login service answered " + reply.code);
      }
      try {
        JSONObject json = new JSONObject(reply.body);
        String access = json.getString("access_token");
        String next = json.optString("refresh_token", "");
        synchronized (this) {
          accessToken = access;
          if (!next.isEmpty()) {
            refreshToken = next;
          }
          expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000;
        }
        save();
        return access;
      } catch (JSONException e) {
        throw new IOException("Spotify's login service sent something unreadable");
      }
    }
  }

  /** Spotify did not accept the token it was given: get a new one the next time. */
  synchronized void tokenRejected() {
    expiresAt = 0;
  }

  private void forget() {
    accessToken = null;
    refreshToken = null;
    expiresAt = 0;
    userId = "";
    userName = "";
  }

  private void save() {
    SharedPreferences.Editor editor = prefs.edit();
    synchronized (this) {
      editor.putString("client_id", clientId).putString("access", accessToken).putString("refresh", refreshToken)
          .putLong("expires", expiresAt).putString("user_id", userId).putString("user_name", userName);
    }
    editor.apply();
  }

  private void changed() {
    main.post(listener::onAccountChanged);
  }

  // ---------------------------------------------------------------- small helpers

  /** The part of a web request's first line that names what is asked for, or null. */
  private static String readTarget(InputStream in) throws IOException {
    // The whole head is read before answering: closing with unread input can make the browser drop the answer.
    StringBuilder head = new StringBuilder();
    int n;
    while (head.length() < 16_384 && (n = in.read()) >= 0) {
      head.append((char) n);
      int length = head.length();
      if (length >= 4 && head.charAt(length - 1) == '\n' && head.charAt(length - 3) == '\n') {
        break;
      }
    }
    int lineEnd = head.indexOf("\r\n");
    String[] parts = (lineEnd < 0 ? head.toString() : head.substring(0, lineEnd)).split(" ");
    return parts.length >= 2 && "GET".equals(parts[0]) ? parts[1] : null;
  }

  private static Map<String, String> query(String target) {
    Map<String, String> out = new HashMap<>();
    int at = target.indexOf('?');
    if (at < 0) {
      return out;
    }
    for (String pair : target.substring(at + 1).split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      try {
        out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
      } catch (UnsupportedEncodingException | IllegalArgumentException e) {
        // A part that cannot be read is left out.
      }
    }
    return out;
  }

  private static void respond(Socket client, String status, String html) throws IOException {
    byte[] body = html.getBytes(StandardCharsets.UTF_8);
    String head = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
        + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
    OutputStream out = client.getOutputStream();
    out.write(head.getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  private static String page(String title, String text) {
    return "<!doctype html><html><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Watch Helper</title>"
        + "<style>body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:56px 24px;text-align:center}"
        + "h1{font-size:22px}p{color:#aaa}a{display:inline-block;margin-top:24px;padding:12px 24px;border-radius:24px;"
        + "background:#1ed760;color:#06210f;text-decoration:none;font-weight:bold}</style></head><body><h1>" + title
        + "</h1><p>" + text + "</p><a href=\"intent://spotify-login#Intent;scheme=watchhelper;"
        + "package=io.github.bahadirkiziltepe.watchhelper;end\">Back to Watch Helper</a></body></html>";
  }

  /** What Spotify says went wrong, from either of the two shapes its error answers come in. */
  static String errorText(String body) {
    String text = "";
    try {
      JSONObject json = new JSONObject(body == null ? "" : body);
      JSONObject error = json.optJSONObject("error");
      if (error != null) {
        text = error.optString("message", "");
        String reason = error.optString("reason", "");
        if (!reason.isEmpty()) {
          text = text.isEmpty() ? reason : text + ", " + reason;
        }
      } else {
        text = json.optString("error", "");
        String more = json.optString("error_description", "");
        if (!more.isEmpty()) {
          text = text.isEmpty() ? more : text + ": " + more;
        }
      }
    } catch (JSONException e) {
      // Not JSON: nothing to quote.
    }
    if (text.isEmpty()) {
      text = "no reason given";
    }
    return text.length() > 140 ? text.substring(0, 140) : text;
  }

  private String randomText(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(UNRESERVED.charAt(random.nextInt(UNRESERVED.length())));
    }
    return sb.toString();
  }

  private static String challenge(String verifier) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String encode(String text) {
    try {
      return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
