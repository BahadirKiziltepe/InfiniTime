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
 * first entry whose time is still ahead.
 *
 * <p>That chance is of anything falling, rain or snow. Which of the two it would be is worked out
 * here, hour by hour and for the day: from how much rain and how much snow the forecast expects,
 * and where it expects neither, from the temperature. The watch shows rain in blue and snow in
 * light blue.
 *
 * <p>Only org.json and plain Java, so this can be checked on a desktop JVM.
 */
final class WeatherReport {
  static final int UNKNOWN = PhoneFrames.UNKNOWN;

  /** Says which variables an answer was asked with. One that was asked with fewer is asked again. */
  static final int FORMAT = 2;

  /** Snowfall comes in centimetres of snow, rain in millimetres of water: 7 cm of snow are 10 mm of water. */
  private static final double SNOW_TO_WATER = 10.0 / 7.0;
  /** At or under this, what falls is taken to be snow where the forecast does not say: 34 °F, 1 °C. */
  private static final double SNOW_UNDER_FAHRENHEIT = 34.0;
  private static final double SNOW_UNDER_CELSIUS = 1.0;

  /** The address to ask for the weather at a place, in the units wanted. Coordinates are rounded by the caller. */
  static String address(double latitude, double longitude, boolean imperial) {
    return String.format(Locale.ROOT,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,weather_code,is_day"
            + "&hourly=precipitation_probability,temperature_2m,rain,showers,snowfall"
            + "&daily=precipitation_probability_max,rain_sum,showers_sum,snowfall_sum"
            + "&timezone=auto&timeformat=unixtime&forecast_days=2%s",
        latitude, longitude, imperial ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
  }

  final boolean imperial;
  /** The weather in a word or two, or empty if the service did not say; and one of PhoneFrames.WEATHER_KIND_. */
  final String words;
  final int kind;
  final int temperature;
  final int feelsLike;
  final int wind;
  /** Percent, or UNKNOWN. */
  final int humidity;
  final int rainToday;
  /** What falls today, if anything does, is snow. */
  final boolean snowToday;
  /** When the numbers are from, on the clock of the place they are for. */
  final int hour;
  final int minute;
  /** The hour of the day (0 to 23) that is under way, and the chance of rain for it and the hours after it. */
  final int firstHour;
  final int[] hourly;
  /** Bit n set: what would fall in the n-th of those hours is snow. */
  final int snowHours;

  private WeatherReport(boolean imperial, String words, int kind, int temperature, int feelsLike, int wind,
      int humidity, int rainToday, boolean snowToday, int hour, int minute, int firstHour, int[] hourly,
      int snowHours) {
    this.imperial = imperial;
    this.words = words;
    this.kind = kind;
    this.temperature = temperature;
    this.feelsLike = feelsLike;
    this.wind = wind;
    this.humidity = humidity;
    this.rainToday = rainToday;
    this.snowToday = snowToday;
    this.hour = hour;
    this.minute = minute;
    this.firstHour = firstHour;
    this.hourly = hourly;
    this.snowHours = snowHours;
  }

  /**
   * Reads the service's answer. {@code nowSeconds} is the time on the phone's clock, in seconds
   * since 1970: it decides which hour is the one under way. Throws if the answer is not a forecast.
   */
  static WeatherReport parse(String body, boolean imperial, long nowSeconds) throws JSONException {
    JSONObject root = new JSONObject(body);
    JSONObject current = root.getJSONObject("current");
    long offset = root.optLong("utc_offset_seconds", 0);
    double exact = current.getDouble("temperature_2m");
    int temperature = (int) Math.round(exact);
    int feelsLike = current.isNull("apparent_temperature") ? temperature
        : (int) Math.round(current.getDouble("apparent_temperature"));
    int wind = current.isNull("wind_speed_10m") ? 0 : (int) Math.round(current.getDouble("wind_speed_10m"));
    int humidity = percent(current, "relative_humidity_2m");
    long at = current.isNull("time") ? nowSeconds : current.getLong("time");
    long local = at + offset;
    int hour = (int) (Math.floorMod(local, 86_400L) / 3600);
    int minute = (int) (Math.floorMod(local, 3600L) / 60);
    int code = current.isNull("weather_code") ? -1 : (int) Math.round(current.getDouble("weather_code"));
    boolean day = current.isNull("is_day") || current.getDouble("is_day") != 0;
    double snowUnder = imperial ? SNOW_UNDER_FAHRENHEIT : SNOW_UNDER_CELSIUS;

    int rainToday = UNKNOWN;
    boolean snowToday = exact <= snowUnder;
    JSONObject daily = root.optJSONObject("daily");
    if (daily != null) {
      JSONArray days = daily.optJSONArray("precipitation_probability_max");
      if (days != null && days.length() > 0 && !days.isNull(0)) {
        rainToday = clampPercent(days.getDouble(0));
      }
      double snow = amount(daily.optJSONArray("snowfall_sum"), 0) * SNOW_TO_WATER;
      double wet = amount(daily.optJSONArray("rain_sum"), 0) + amount(daily.optJSONArray("showers_sum"), 0);
      if (snow > 0 || wet > 0) {
        snowToday = snow >= wet;
      }
    }

    int firstHour = hour;
    int[] hourly = new int[0];
    int snowHours = 0;
    JSONObject hours = root.optJSONObject("hourly");
    JSONArray times = hours == null ? null : hours.optJSONArray("time");
    JSONArray chances = hours == null ? null : hours.optJSONArray("precipitation_probability");
    if (times != null && chances != null) {
      JSONArray temperatures = hours.optJSONArray("temperature_2m");
      JSONArray rains = hours.optJSONArray("rain");
      JSONArray showers = hours.optJSONArray("showers");
      JSONArray snows = hours.optJSONArray("snowfall");
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
          int i = first + k;
          hourly[k] = chances.isNull(i) ? UNKNOWN : clampPercent(chances.getDouble(i));
          double snow = amount(snows, i) * SNOW_TO_WATER;
          double wet = amount(rains, i) + amount(showers, i);
          boolean snowy;
          if (snow > 0 || wet > 0) {
            snowy = snow >= wet;
          } else {
            // The forecast expects nothing to fall in this hour. If something did, the cold decides what.
            snowy = temperatures != null && !temperatures.isNull(i) && temperatures.getDouble(i) <= snowUnder;
          }
          if (snowy) {
            snowHours |= 1 << k;
          }
        }
      }
    }
    return new WeatherReport(imperial, words(code, day), kind(code, day), temperature, feelsLike, wind, humidity,
        rainToday, snowToday, hour, minute, firstHour, hourly, snowHours);
  }

  /**
   * The weather in a word or two, from the number weather services use for it (the WMO weather
   * code). Empty for a number that is not one of them. At most 18 characters, which is what the
   * watch keeps.
   */
  static String words(int code, boolean day) {
    switch (code) {
      case 0:
        return day ? "Sunny" : "Clear";
      case 1:
        return day ? "Mostly sunny" : "Mostly clear";
      case 2:
        return "Partly cloudy";
      case 3:
        return "Cloudy";
      case 45:
      case 48:
        return "Fog";
      case 51:
      case 53:
      case 55:
        return "Drizzle";
      case 56:
      case 57:
        return "Freezing drizzle";
      case 61:
        return "Light rain";
      case 63:
        return "Rain";
      case 65:
        return "Heavy rain";
      case 66:
      case 67:
        return "Freezing rain";
      case 71:
        return "Light snow";
      case 73:
      case 77:
        return "Snow";
      case 75:
        return "Heavy snow";
      case 80:
        return "Light showers";
      case 81:
        return "Showers";
      case 82:
        return "Heavy showers";
      case 85:
        return "Snow showers";
      case 86:
        return "Heavy snow showers";
      case 95:
        return "Thunderstorm";
      case 96:
      case 99:
        return "Thunderstorm, hail";
      default:
        return "";
    }
  }

  /** What kind of weather that is, which decides the colour of the words on the watch. */
  static int kind(int code, boolean day) {
    if (code == 0 || code == 1) {
      return day ? PhoneFrames.WEATHER_KIND_SUN : PhoneFrames.WEATHER_KIND_PLAIN;
    }
    if (code == 2 || code == 3 || code == 45 || code == 48) {
      return PhoneFrames.WEATHER_KIND_CLOUD;
    }
    if (code == 56 || code == 57 || code == 66 || code == 67) {
      return PhoneFrames.WEATHER_KIND_ICE;
    }
    if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82)) {
      return PhoneFrames.WEATHER_KIND_RAIN;
    }
    if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
      return PhoneFrames.WEATHER_KIND_SNOW;
    }
    if (code == 95 || code == 96 || code == 99) {
      return PhoneFrames.WEATHER_KIND_STORM;
    }
    return PhoneFrames.WEATHER_KIND_NONE;
  }

  /** The messages that carry this to the watch. {@code status} is PhoneFrames.WEATHER_FRESH or WEATHER_OLD. */
  List<byte[]> frames(int status) {
    return PhoneFrames.weather(status, imperial, imperial, snowToday, temperature, feelsLike, wind, humidity,
        rainToday, hour, minute, firstHour, hourly, snowHours, words, kind);
  }

  /** One line for the app's screen, such as "Cloudy, 54°F, feels like 50°F. Wind 12 mph, rain today 60%." */
  String sentence() {
    String unit = imperial ? "°F" : "°C";
    StringBuilder sb = new StringBuilder();
    if (!words.isEmpty()) {
      sb.append(words).append(", ");
    }
    sb.append(temperature).append(unit).append(", feels like ").append(feelsLike).append(unit).append(". Wind ")
        .append(wind).append(imperial ? " mph" : " km/h");
    if (humidity != UNKNOWN) {
      sb.append(", humidity ").append(humidity).append('%');
    }
    if (rainToday != UNKNOWN) {
      sb.append(snowToday ? ", snow today " : ", rain today ").append(rainToday).append('%');
    }
    return sb.append('.').toString();
  }

  /** The amount at a place in a list of amounts, or 0 if the list has none there. */
  private static double amount(JSONArray from, int index) throws JSONException {
    return from == null || index >= from.length() || from.isNull(index) ? 0 : from.getDouble(index);
  }

  private static int percent(JSONObject from, String name) throws JSONException {
    return from.isNull(name) ? UNKNOWN : clampPercent(from.getDouble(name));
  }

  private static int clampPercent(double value) {
    return (int) Math.max(0, Math.min(100, Math.round(value)));
  }
}
