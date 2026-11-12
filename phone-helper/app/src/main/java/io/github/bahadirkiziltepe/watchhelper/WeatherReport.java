package io.github.bahadirkiziltepe.watchhelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * The weather as the watch shows it, read from an answer of the Open-Meteo forecast service
 * (https://open-meteo.com, data under CC BY 4.0).
 *
 * <p>The answer is asked for with times as seconds since 1970 (timeformat=unixtime), the place's
 * own time zone (timezone=auto) and two days of hours. Its hourly chance of rain is "the
 * probability of precipitation in the hour before", so the hour that is under way right now is the
 * first entry whose time is still ahead. That chance counts snow as well as rain.
 *
 * <p>Only org.json and plain Java, so this can be checked on a desktop JVM.
 */
final class WeatherReport {
  static final int UNKNOWN = PhoneFrames.UNKNOWN;

  /** The address to ask for the weather at a place, in the units wanted. Coordinates are rounded by the caller. */
  static String address(double latitude, double longitude, boolean imperial) {
    return String.format(Locale.ROOT,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m"
            + "&hourly=precipitation_probability&daily=precipitation_probability_max"
            + "&timezone=auto&timeformat=unixtime&forecast_days=2%s",
        latitude, longitude, imperial ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
  }

  final boolean imperial;
  final int temperature;
  final int feelsLike;
  final int wind;
  /** Percent, or UNKNOWN. */
  final int humidity;
  final int rainToday;
  /** When the numbers are from, on the clock of the place they are for. */
  final int hour;
  final int minute;
  /** The hour of the day (0 to 23) that is under way, and the chance of rain for it and the hours after it. */
  final int firstHour;
  final int[] hourly;

  private WeatherReport(boolean imperial, int temperature, int feelsLike, int wind, int humidity, int rainToday,
      int hour, int minute, int firstHour, int[] hourly) {
    this.imperial = imperial;
    this.temperature = temperature;
    this.feelsLike = feelsLike;
    this.wind = wind;
    this.humidity = humidity;
    this.rainToday = rainToday;
    this.hour = hour;
    this.minute = minute;
    this.firstHour = firstHour;
    this.hourly = hourly;
  }

  /**
   * Reads the service's answer. {@code nowSeconds} is the time on the phone's clock, in seconds
   * since 1970: it decides which hour is the one under way. Throws if the answer is not a forecast.
   */
  static WeatherReport parse(String body, boolean imperial, long nowSeconds) throws JSONException {
    JSONObject root = new JSONObject(body);
    JSONObject current = root.getJSONObject("current");
    long offset = root.optLong("utc_offset_seconds", 0);
    int temperature = (int) Math.round(current.getDouble("temperature_2m"));
    int feelsLike = current.isNull("apparent_temperature") ? temperature
        : (int) Math.round(current.getDouble("apparent_temperature"));
    int wind = current.isNull("wind_speed_10m") ? 0 : (int) Math.round(current.getDouble("wind_speed_10m"));
    int humidity = percent(current, "relative_humidity_2m");
    long at = current.isNull("time") ? nowSeconds : current.getLong("time");
    long local = at + offset;
    int hour = (int) (Math.floorMod(local, 86_400L) / 3600);
    int minute = (int) (Math.floorMod(local, 3600L) / 60);

    int rainToday = UNKNOWN;
    JSONObject daily = root.optJSONObject("daily");
    JSONArray days = daily == null ? null : daily.optJSONArray("precipitation_probability_max");
    if (days != null && days.length() > 0 && !days.isNull(0)) {
      rainToday = clampPercent(days.getDouble(0));
    }

    int firstHour = hour;
    int[] hourly = new int[0];
    JSONObject hours = root.optJSONObject("hourly");
    JSONArray times = hours == null ? null : hours.optJSONArray("time");
    JSONArray chances = hours == null ? null : hours.optJSONArray("precipitation_probability");
    if (times != null && chances != null) {
      int count = Math.min(times.length(), chances.length());
      int first = -1;
      for (int i = 0; i < count; i++) {
        if (!times.isNull(i) && times.getLong(i) > nowSeconds) {
          first = i;
          break;
        }
      }
      if (first >= 0) {
        // An entry stands for the hour that ends at its time
        firstHour = (int) (Math.floorMod(times.getLong(first) - 3600 + offset, 86_400L) / 3600);
        hourly = new int[Math.min(PhoneFrames.WEATHER_MAX_HOURS, count - first)];
        for (int k = 0; k < hourly.length; k++) {
          hourly[k] = chances.isNull(first + k) ? UNKNOWN : clampPercent(chances.getDouble(first + k));
        }
      }
    }
    return new WeatherReport(imperial, temperature, feelsLike, wind, humidity, rainToday, hour, minute, firstHour,
        hourly);
  }

  /** The messages that carry this to the watch. {@code status} is PhoneFrames.WEATHER_FRESH or WEATHER_OLD. */
  List<byte[]> frames(int status) {
    return PhoneFrames.weather(status, imperial, imperial, temperature, feelsLike, wind, humidity, rainToday, hour,
        minute, firstHour, hourly);
  }

  /** One line for the app's screen, such as "54°F, feels like 50°F. Wind 12 mph, rain today 60%." */
  String sentence() {
    String unit = imperial ? "°F" : "°C";
    StringBuilder sb = new StringBuilder();
    sb.append(temperature).append(unit).append(", feels like ").append(feelsLike).append(unit).append(". Wind ")
        .append(wind).append(imperial ? " mph" : " km/h");
    if (humidity != UNKNOWN) {
      sb.append(", humidity ").append(humidity).append('%');
    }
    if (rainToday != UNKNOWN) {
      sb.append(", rain today ").append(rainToday).append('%');
    }
    return sb.append('.').toString();
  }

  private static int percent(JSONObject from, String name) throws JSONException {
    return from.isNull(name) ? UNKNOWN : clampPercent(from.getDouble(name));
  }

  private static int clampPercent(double value) {
    return (int) Math.max(0, Math.min(100, Math.round(value)));
  }
}
