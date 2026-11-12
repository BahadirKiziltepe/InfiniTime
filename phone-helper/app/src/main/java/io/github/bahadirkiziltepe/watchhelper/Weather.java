package io.github.bahadirkiziltepe.watchhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;

import org.json.JSONException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The weather for the watch's weather screen: where the phone is, what the Open-Meteo forecast
 * service says for that place, and the answer to the watch.
 *
 * <p>Where the phone is: only the rough position is asked for, and it is rounded to two decimals
 * (about a kilometre) before anything is done with it. That rounded position goes to the weather
 * service, over an encrypted connection, and is kept in the app's private storage so there is
 * weather when Android will not say where the phone is (in the background that takes "Allow all
 * the time"). It is never written to the log or the report.
 *
 * <p>The service's last answer is kept as it came, so it can be read again later: the hours of
 * rain then start at the hour it has become, and the watch is told that the numbers are old.
 */
final class Weather {
  private static final String PREFS = "weather";
  private static final String KEY_BODY = "answer";
  private static final String KEY_BODY_AT = "answer_at";
  private static final String KEY_BODY_IMPERIAL = "answer_imperial";
  private static final String KEY_BODY_LATITUDE = "answer_latitude";
  private static final String KEY_BODY_LONGITUDE = "answer_longitude";
  private static final String KEY_PLACE = "place";
  private static final String KEY_LATITUDE = "latitude";
  private static final String KEY_LONGITUDE = "longitude";
  private static final String KEY_PLACE_AT = "place_at";
  private static final String KEY_IMPERIAL = "imperial";

  /** An answer this recent is passed on without asking again. */
  private static final long FRESH_MS = 10 * 60_000L;
  /** An answer older than this is not shown any more. */
  private static final long KEEP_MS = 3 * 3_600_000L;
  /** A position this recent is used as it is. */
  private static final long FIX_FRESH_MS = 30 * 60_000L;
  private static final long FIX_WAIT_MS = 8_000;
  /** Moved further than this (about five kilometres) since the last answer: ask again. */
  private static final float MOVED_DEGREES = 0.05f;

  /** Where degrees are Fahrenheit and wind is in miles per hour. */
  private static final List<String> IMPERIAL_COUNTRIES =
      Arrays.asList("US", "PR", "GU", "VI", "AS", "MP", "UM", "BS", "BZ", "KY", "PW", "FM", "MH", "LR");

  private final Context app;
  private final Handler main;
  private final EventLog log;
  private final WatchLink watch;
  private final Runnable changed;
  private final SharedPreferences prefs;

  private final ExecutorService io = Executors.newSingleThreadExecutor(work -> {
    Thread thread = new Thread(work, "weather");
    thread.setDaemon(true);
    return thread;
  });

  /** True while the app's screen is open: Android then says where the phone is without "all the time". */
  private volatile boolean screenOpen;
  private volatile WeatherReport latest;
  private volatile long latestAt;
  private volatile String lastProblem = "";
  private volatile int asked;
  private volatile int answered;
  private volatile int fetched;

  Weather(Context app, Handler main, EventLog log, WatchLink watch, Runnable changed) {
    this.app = app;
    this.main = main;
    this.log = log;
    this.watch = watch;
    this.changed = changed;
    this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  // ---------------------------------------------------------------- asked for

  /** The watch opened its weather screen. Main thread. */
  void onWatchAsks() {
    asked++;
    io.execute(() -> answer(true, false));
  }

  /** The app's screen opened: a good moment to learn where the phone is and to have numbers ready. */
  void onScreenOpened() {
    screenOpen = true;
    io.execute(() -> {
      // While the screen is open Android says where the phone is. Remember it for later.
      locate();
      answer(false, false);
    });
  }

  void onScreenClosed() {
    screenOpen = false;
  }

  /** The button on the screen. */
  void refresh() {
    io.execute(() -> answer(false, true));
  }

  // ---------------------------------------------------------------- units

  /** Fahrenheit and miles per hour, or Celsius and kilometres per hour. */
  boolean imperial() {
    if (prefs.contains(KEY_IMPERIAL)) {
      return prefs.getBoolean(KEY_IMPERIAL, false);
    }
    return IMPERIAL_COUNTRIES.contains(Locale.getDefault().getCountry());
  }

  void setImperial(boolean imperial) {
    prefs.edit().putBoolean(KEY_IMPERIAL, imperial).apply();
    refresh();
  }

  // ---------------------------------------------------------------- permissions

  boolean mayLocate() {
    return has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION);
  }

  boolean mayLocateInBackground() {
    return mayLocate() && has(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
  }

  private boolean has(String permission) {
    return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  // ---------------------------------------------------------------- the work (background thread)

  private void answer(boolean toWatch, boolean askAgain) {
    long now = System.currentTimeMillis();
    boolean imperial = imperial();
    // What there is goes out at once; newer numbers follow if they can be had.
    WeatherReport kept = null;
    long keptAt = prefs.getLong(KEY_BODY_AT, 0);
    boolean keptImperial = prefs.getBoolean(KEY_BODY_IMPERIAL, false);
    String body = prefs.getString(KEY_BODY, null);
    if (body != null && keptAt > 0 && now - keptAt < KEEP_MS && now >= keptAt) {
      try {
        kept = WeatherReport.parse(body, keptImperial, now / 1000);
      } catch (JSONException | RuntimeException e) {
        kept = null;
      }
    }
    boolean moved = prefs.getBoolean(KEY_PLACE, false)
        && (Math.abs(prefs.getFloat(KEY_LATITUDE, 0) - prefs.getFloat(KEY_BODY_LATITUDE, 0)) > MOVED_DEGREES
            || Math.abs(prefs.getFloat(KEY_LONGITUDE, 0) - prefs.getFloat(KEY_BODY_LONGITUDE, 0)) > MOVED_DEGREES);
    boolean fresh = !askAgain && !moved && kept != null && now - keptAt < FRESH_MS && keptImperial == imperial;
    if (kept != null) {
      show(kept, keptAt);
      if (toWatch) {
        send(kept.frames(fresh ? PhoneFrames.WEATHER_FRESH : PhoneFrames.WEATHER_OLD));
      }
    }
    if (fresh) {
      return;
    }
    double[] place = locate();
    if (place == null) {
      lastProblem = mayLocate() ? "Android does not say where the phone is" : "the Location permission is missing";
      if (toWatch && kept == null) {
        send(PhoneFrames.noWeather(mayLocate() ? PhoneFrames.WEATHER_NO_PLACE : PhoneFrames.WEATHER_NO_PERMISSION));
      }
      main.post(changed);
      return;
    }
    try {
      // The address holds the position, so neither it nor an error text that may quote it is ever logged
      Http.Reply reply = Http.send("GET", WeatherReport.address(place[0], place[1], imperial), null, null, null);
      if (!reply.ok()) {
        throw new IOException("answer " + reply.code);
      }
      WeatherReport report = WeatherReport.parse(reply.body, imperial, System.currentTimeMillis() / 1000);
      long at = System.currentTimeMillis();
      prefs.edit().putString(KEY_BODY, reply.body).putLong(KEY_BODY_AT, at).putBoolean(KEY_BODY_IMPERIAL, imperial)
          .putFloat(KEY_BODY_LATITUDE, (float) place[0]).putFloat(KEY_BODY_LONGITUDE, (float) place[1]).apply();
      fetched++;
      lastProblem = "";
      show(report, at);
      if (toWatch) {
        send(report.frames(PhoneFrames.WEATHER_FRESH));
      }
    } catch (IOException | JSONException | RuntimeException e) {
      lastProblem = "the weather service did not answer (" + e.getClass().getSimpleName() + ")";
      log.add("weather", "no new weather: " + e.getClass().getSimpleName());
      if (toWatch && kept == null) {
        send(PhoneFrames.noWeather(PhoneFrames.WEATHER_NO_ANSWER));
      }
    }
    main.post(changed);
  }

  private void show(WeatherReport report, long at) {
    latest = report;
    latestAt = at;
  }

  private void send(final List<byte[]> frames) {
    main.post(() -> {
      if (watch.sendPhone(frames)) {
        answered++;
      }
    });
  }

  /**
   * Where the phone is, as latitude and longitude rounded to two decimals; or where it was the
   * last time Android said; or null if it never did.
   */
  @SuppressLint("MissingPermission")
  private double[] locate() {
    if (mayLocate() && (screenOpen || mayLocateInBackground())) {
      try {
        LocationManager manager = app.getSystemService(LocationManager.class);
        if (manager != null) {
          Location best = lastKnown(manager);
          if (best == null || System.currentTimeMillis() - best.getTime() > FIX_FRESH_MS) {
            Location now = current(manager);
            if (now != null) {
              best = now;
            }
          }
          if (best != null) {
            prefs.edit().putBoolean(KEY_PLACE, true).putFloat(KEY_LATITUDE, round(best.getLatitude()))
                .putFloat(KEY_LONGITUDE, round(best.getLongitude())).putLong(KEY_PLACE_AT, System.currentTimeMillis())
                .apply();
          }
        }
      } catch (RuntimeException e) {
        // Falls back to where the phone was last seen.
      }
    }
    if (!prefs.getBoolean(KEY_PLACE, false)) {
      return null;
    }
    return new double[] {prefs.getFloat(KEY_LATITUDE, 0), prefs.getFloat(KEY_LONGITUDE, 0)};
  }

  private static float round(double degrees) {
    return Math.round(degrees * 100.0) / 100f;
  }

  /** The newest position Android remembers, from whichever source. */
  @SuppressLint("MissingPermission")
  private static Location lastKnown(LocationManager manager) {
    Location best = null;
    for (String provider : manager.getProviders(true)) {
      try {
        Location known = manager.getLastKnownLocation(provider);
        if (known != null && (best == null || known.getTime() > best.getTime())) {
          best = known;
        }
      } catch (RuntimeException e) {
        // Not this source, then.
      }
    }
    return best;
  }

  /** Asks Android where the phone is right now, and waits a few seconds for the answer. */
  @SuppressLint("MissingPermission")
  private static Location current(LocationManager manager) {
    String provider = manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
        : manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
    if (provider == null) {
      return null;
    }
    final CountDownLatch done = new CountDownLatch(1);
    final Location[] found = new Location[1];
    CancellationSignal cancel = new CancellationSignal();
    try {
      manager.getCurrentLocation(provider, cancel, Runnable::run, location -> {
        found[0] = location;
        done.countDown();
      });
      if (!done.await(FIX_WAIT_MS, TimeUnit.MILLISECONDS)) {
        cancel.cancel();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      // No answer from this source.
    }
    return found[0];
  }

  // ---------------------------------------------------------------- status text

  /** One line for the screen. */
  String describe() {
    WeatherReport report = latest;
    boolean old = report != null && System.currentTimeMillis() - latestAt > KEEP_MS;
    if (report != null && !old) {
      String from = String.format(Locale.ROOT, "%d:%02d", report.hour, report.minute);
      String note = lastProblem.isEmpty() ? "" : " Could not get newer numbers: " + lastProblem + ".";
      return "✓ Weather: " + report.sentence() + " From " + from + "." + note;
    }
    if (!mayLocate() && !prefs.getBoolean(KEY_PLACE, false)) {
      return "✗ Weather: the watch's weather screen needs to know roughly where the phone is. Tap Allow location.";
    }
    if (!lastProblem.isEmpty()) {
      return "✗ Weather: " + lastProblem + ".";
    }
    return "Weather: nothing yet. It is fetched when the watch asks, or when you tap Refresh.";
  }

  /** For the report. Never where the phone is. */
  String dump() {
    long now = System.currentTimeMillis();
    long placeAt = prefs.getLong(KEY_PLACE_AT, 0);
    long bodyAt = prefs.getLong(KEY_BODY_AT, 0);
    return "Location allowed: " + (mayLocate() ? "yes" : "no") + ", in the background: "
        + (mayLocateInBackground() ? "yes" : "no") + "\nPlace known: "
        + (prefs.getBoolean(KEY_PLACE, false) ? "yes, " + (now - placeAt) / 60_000 + " min old" : "no")
        + "\nLast answer of the weather service: " + (bodyAt > 0 ? (now - bodyAt) / 60_000 + " min old" : "none")
        + ", units: " + (imperial() ? "Fahrenheit, mph" : "Celsius, km/h") + "\nWeather asked for by the watch: " + asked
        + ", answers sent: " + answered + ", fetched: " + fetched
        + (lastProblem.isEmpty() ? "" : "\nLast problem: " + lastProblem);
  }
}
