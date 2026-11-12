#include "displayapp/screens/PhoneWeather.h"
#include <task.h>
#include "displayapp/InfiniTimeTheme.h"
#include "components/ble/BleController.h"
#include "components/settings/Settings.h"

using namespace Pinetime::Applications::Screens;

namespace {
  constexpr uint32_t refreshPeriodMs = 500;

  // The phone may first have to find out where it is and then ask the weather service
  constexpr TickType_t answerTimeout = pdMS_TO_TICKS(15000);
  // With no answer, the phone is asked again this often while the screen stays open
  constexpr TickType_t askAgainAfter = pdMS_TO_TICKS(20000);
  // Numbers older than this are not shown any more. (Counted in ticks directly: the usual
  // conversion from milliseconds overflows for anything longer than about an hour.)
  constexpr TickType_t tooOld = static_cast<TickType_t>(3) * 60 * 60 * configTICK_RATE_HZ;

  constexpr lv_coord_t margin = 6;
  constexpr lv_coord_t noteWidth = 224;
  constexpr uint8_t hoursPerRow = 5;

  constexpr lv_color_t hourColor = LV_COLOR_MAKE(0x90, 0x90, 0x90);
  // Numbers that are not what the phone would send right now
  constexpr lv_color_t staleColor = LV_COLOR_MAKE(0x80, 0x80, 0x80);

  // The colour of each kind of weather: of the words at the top, and of a chance of rain or snow
  constexpr lv_color_t sunColor = LV_COLOR_MAKE(0xff, 0xd0, 0x00);
  constexpr lv_color_t cloudColor = LV_COLOR_MAKE(0xb0, 0xb0, 0xb0);
  constexpr lv_color_t rainColor = LV_COLOR_MAKE(0x4a, 0x90, 0xff);
  constexpr lv_color_t snowColor = LV_COLOR_MAKE(0xa6, 0xe3, 0xff);
  constexpr lv_color_t iceColor = LV_COLOR_MAKE(0xc5, 0x8c, 0xff);
  constexpr lv_color_t stormColor = LV_COLOR_MAKE(0xff, 0x9a, 0x30);

  // The same blue and light blue the way LVGL wants a colour inside a text, and the grey of a
  // chance too small to be worth a colour
  constexpr const char* rainText = "4a90ff";
  constexpr const char* snowText = "a6e3ff";
  constexpr const char* littleText = "909090";
  constexpr uint8_t worthAColorFrom = 20;

  lv_color_t ColorOf(uint8_t kind) {
    using Music = Pinetime::Controllers::MusicService;
    switch (kind) {
      case Music::WeatherKindSun:
        return sunColor;
      case Music::WeatherKindCloud:
        return cloudColor;
      case Music::WeatherKindRain:
        return rainColor;
      case Music::WeatherKindSnow:
        return snowColor;
      case Music::WeatherKindIce:
        return iceColor;
      case Music::WeatherKindStorm:
        return stormColor;
      default:
        return LV_COLOR_WHITE;
    }
  }

  constexpr const char* degree = "\xC2\xB0";

  // An hour of the day in three characters: " 5p", "12a", or " 17" on a 24-hour clock
  void HourCell(char* out, uint8_t hour, bool twelveHours) {
    if (twelveHours) {
      const int shown = hour % 12 == 0 ? 12 : hour % 12;
      lv_snprintf(out, 4, "%2d%c", shown, hour < 12 ? 'a' : 'p');
    } else {
      lv_snprintf(out, 4, "%3d", static_cast<int>(hour));
    }
  }

  // The time the numbers are from: "4:35p", or "16:35" on a 24-hour clock
  void ClockText(char* out, size_t size, uint8_t hour, uint8_t minute, bool twelveHours) {
    if (twelveHours) {
      const int shown = hour % 12 == 0 ? 12 : hour % 12;
      lv_snprintf(out, size, "%d:%02d%c", shown, static_cast<int>(minute), hour < 12 ? 'a' : 'p');
    } else {
      lv_snprintf(out, size, "%d:%02d", static_cast<int>(hour), static_cast<int>(minute));
    }
  }
}

PhoneWeather::PhoneWeather(Pinetime::Controllers::MusicService& musicService,
                           const Pinetime::Controllers::Ble& bleController,
                           Pinetime::Controllers::Settings& settingsController)
  : musicService {musicService}, bleController {bleController}, settingsController {settingsController} {
  labelTitle = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelTitle, "Weather");
  lv_obj_set_pos(labelTitle, margin, 0);

  labelCondition = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelCondition, "");
  lv_obj_align(labelCondition, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -margin, 0);

  labelTime = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelTime, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::orange);
  lv_label_set_text_static(labelTime, "");
  lv_obj_set_pos(labelTime, margin, 0);

  labelTemperature = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_font(labelTemperature, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &jetbrains_mono_42);
  lv_label_set_text_static(labelTemperature, "");
  lv_obj_set_pos(labelTemperature, margin, 24);

  labelFeels = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_align(labelFeels, LV_LABEL_ALIGN_RIGHT);
  lv_label_set_text_static(labelFeels, "");
  lv_obj_align(labelFeels, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -margin, 22);

  labelWind = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelWind, "");
  lv_obj_set_pos(labelWind, margin, 74);

  labelHumidity = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelHumidity, "");
  lv_obj_set_pos(labelHumidity, margin, 96);

  labelRain = lv_label_create(lv_scr_act(), nullptr);
  // The percentage carries the colour of what would fall
  lv_label_set_recolor(labelRain, true);
  lv_label_set_text_static(labelRain, "");
  lv_obj_set_pos(labelRain, margin, 118);

  // Each pair of rows is five cells of three characters with a space between them
  constexpr std::array<lv_coord_t, 2> hoursY = {142, 188};
  constexpr std::array<lv_coord_t, 2> chancesY = {164, 210};
  for (size_t row = 0; row < labelHours.size(); row++) {
    labelHours[row] = lv_label_create(lv_scr_act(), nullptr);
    lv_obj_set_style_local_text_color(labelHours[row], LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, hourColor);
    lv_label_set_text_static(labelHours[row], "");
    lv_obj_set_pos(labelHours[row], margin, hoursY[row]);

    labelChances[row] = lv_label_create(lv_scr_act(), nullptr);
    lv_label_set_recolor(labelChances[row], true);
    lv_label_set_text_static(labelChances[row], "");
    lv_obj_set_pos(labelChances[row], margin, chancesY[row]);
  }

  labelNote = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelNote, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_long_mode(labelNote, LV_LABEL_LONG_BREAK);
  lv_obj_set_width(labelNote, noteWidth);
  lv_label_set_align(labelNote, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text_static(labelNote, "");
  lv_obj_align(labelNote, lv_scr_act(), LV_ALIGN_CENTER, 0, 0);

  const TickType_t now = xTaskGetTickCount();
  // What the phone sent the last time is shown until its new answer is here
  seenRevision = musicService.getWeatherRevision();
  if (!musicService.ReadWeather(weather)) {
    weather = {};
    // It was being replaced at that very moment: Refresh() picks it up
    seenRevision--;
  }
  connected = bleController.IsConnected();
  if (connected) {
    Ask(now);
  }
  Draw(now);

  taskRefresh = lv_task_create(RefreshTaskCallback, refreshPeriodMs, LV_TASK_PRIO_MID, this);
}

PhoneWeather::~PhoneWeather() {
  lv_task_del(taskRefresh);
  lv_obj_clean(lv_scr_act());
}

void PhoneWeather::Ask(TickType_t now) {
  waiting = true;
  unanswered = false;
  askedTick = now;
  musicService.RequestWeather();
}

void PhoneWeather::Refresh() {
  const TickType_t now = xTaskGetTickCount();

  const bool nowConnected = bleController.IsConnected();
  if (nowConnected != connected) {
    connected = nowConnected;
    waiting = false;
    unanswered = false;
    if (connected) {
      Ask(now);
    }
    needsDraw = true;
  }

  const uint32_t revision = musicService.getWeatherRevision();
  if (revision != seenRevision && musicService.ReadWeather(weather)) {
    seenRevision = revision;
    waiting = false;
    unanswered = false;
    needsDraw = true;
  }

  if (waiting && (now - askedTick) >= answerTimeout) {
    waiting = false;
    unanswered = true;
    needsDraw = true;
  }
  if (connected && unanswered && (now - askedTick) >= askAgainAfter) {
    Ask(now);
    needsDraw = true;
  }

  if (needsDraw) {
    Draw(now);
  }
}

void PhoneWeather::Draw(TickType_t now) {
  needsDraw = false;
  const bool twelveHours = settingsController.GetClockType() == Pinetime::Controllers::Settings::ClockType::H12;
  const bool numbers = weather.hasNumbers() && (now - weather.arrived) < tooOld;

  lv_obj_set_hidden(labelTitle, numbers);
  lv_obj_set_hidden(labelCondition, !numbers);
  lv_obj_set_hidden(labelTime, true);
  lv_obj_set_hidden(labelTemperature, !numbers);
  lv_obj_set_hidden(labelFeels, !numbers);
  lv_obj_set_hidden(labelWind, !numbers);
  lv_obj_set_hidden(labelHumidity, !numbers);
  lv_obj_set_hidden(labelRain, !numbers);
  for (size_t row = 0; row < labelHours.size(); row++) {
    lv_obj_set_hidden(labelHours[row], !numbers);
    lv_obj_set_hidden(labelChances[row], !numbers);
  }
  lv_obj_set_hidden(labelNote, numbers);

  if (!numbers) {
    const char* note = "Asking the phone...";
    if (!connected) {
      note = "Phone not connected";
    } else if (waiting) {
      note = "Asking the phone...";
    } else if (unanswered) {
      note = "No answer from\nthe phone.";
    } else if (weather.status == Music::WeatherNoPermission) {
      note = "The phone app may\nnot see where the\nphone is. Open Watch\nHelper and allow it.";
    } else if (weather.status == Music::WeatherNoPlace) {
      note = "The phone does not\nknow where it is yet.\nOpen Watch Helper\non the phone once.";
    } else if (weather.status == Music::WeatherNoAnswer) {
      note = "No weather right now.\nIs the phone online?";
    }
    lv_label_set_text_static(labelNote, note);
    lv_obj_align(labelNote, lv_scr_act(), LV_ALIGN_CENTER, 0, 0);
    return;
  }

  // Numbers that are not what the phone would send right now (it could not get newer ones, it is
  // not connected, or it has not answered) are greyed, and the time they are from is shown.
  const bool stale = weather.status == Music::WeatherOld || !connected || unanswered;
  const lv_color_t numberColor = stale ? staleColor : LV_COLOR_WHITE;
  char clock[12];
  ClockText(clock, sizeof(clock), weather.hour, weather.minute, twelveHours);

  if (weather.condition[0] != '\0') {
    // The weather in words, in the colour of its kind
    lv_label_set_text(labelCondition, weather.condition.data());
    lv_obj_set_style_local_text_color(labelCondition, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, ColorOf(weather.conditionKind));
    lv_obj_align(labelCondition, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -margin, 0);
    if (stale) {
      // The time goes in the corner the words leave free, if they leave enough of it
      lv_label_set_text(labelTime, clock);
      const lv_coord_t room = LV_HOR_RES_MAX - 2 * margin - lv_obj_get_width(labelCondition);
      lv_obj_set_hidden(labelTime, lv_obj_get_width(labelTime) + 2 * margin > room);
    }
  } else {
    // A phone app that does not send the weather in words: the time, where the words would be
    lv_label_set_text(labelCondition, clock);
    lv_obj_set_style_local_text_color(labelCondition, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, stale ? Colors::orange : Colors::lightGray);
    lv_obj_align(labelCondition, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -margin, 0);
  }

  lv_obj_set_style_local_text_color(labelTemperature, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, numberColor);
  lv_obj_set_style_local_text_color(labelFeels, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, numberColor);
  lv_obj_set_style_local_text_color(labelWind, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, numberColor);
  lv_obj_set_style_local_text_color(labelHumidity, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, numberColor);
  lv_obj_set_style_local_text_color(labelRain, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, numberColor);

  lv_label_set_text_fmt(labelTemperature, "%d%s%c", static_cast<int>(weather.temperature), degree, weather.fahrenheit ? 'F' : 'C');
  lv_label_set_text_fmt(labelFeels, "Feels\n%d%s", static_cast<int>(weather.feelsLike), degree);
  lv_obj_align(labelFeels, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -margin, 22);

  lv_label_set_text_fmt(labelWind, "Wind %d %s", static_cast<int>(weather.wind), weather.milesPerHour ? "mph" : "km/h");
  if (weather.humidity <= 100) {
    lv_label_set_text_fmt(labelHumidity, "Humidity %d%%", static_cast<int>(weather.humidity));
  } else {
    lv_label_set_text_static(labelHumidity, "Humidity --");
  }

  // The day's chance of rain, or of snow if that is what would fall: the number in blue or light
  // blue, unless the chance is too small to be worth a colour
  const char* falling = weather.snowToday ? "Snow" : "Rain";
  if (weather.rainToday > 100) {
    lv_label_set_text_fmt(labelRain, "%s today --", falling);
  } else if (weather.rainToday >= worthAColorFrom) {
    lv_label_set_text_fmt(labelRain,
                          "%s today #%s %d%%#",
                          falling,
                          weather.snowToday ? snowText : rainText,
                          static_cast<int>(weather.rainToday));
  } else {
    lv_label_set_text_fmt(labelRain, "%s today %d%%", falling, static_cast<int>(weather.rainToday));
  }

  for (size_t row = 0; row < labelHours.size(); row++) {
    // Five cells of three characters and the spaces between them; a chance also carries its colour
    char hours[hoursPerRow * 4 + 1];
    char chances[hoursPerRow * 13 + 1];
    size_t hoursUsed = 0;
    size_t chancesUsed = 0;
    hours[0] = '\0';
    chances[0] = '\0';
    for (uint8_t cell = 0; cell < hoursPerRow; cell++) {
      const size_t index = row * hoursPerRow + cell;
      if (index >= weather.hours) {
        break;
      }
      if (cell > 0) {
        hours[hoursUsed++] = ' ';
        chances[chancesUsed++] = ' ';
      }
      HourCell(hours + hoursUsed, static_cast<uint8_t>((weather.firstHour + index) % 24), twelveHours);
      hoursUsed += 3;
      const uint8_t chance = weather.rain[index];
      if (chance <= 100) {
        // Blue for rain, light blue for snow, grey for a chance too small to matter
        const bool snow = (weather.snowHours & (1u << index)) != 0;
        const char* color = chance < worthAColorFrom ? littleText : snow ? snowText : rainText;
        lv_snprintf(chances + chancesUsed, 13, "#%s %3d#", color, static_cast<int>(chance));
      } else {
        lv_snprintf(chances + chancesUsed, 13, "#%s  --#", littleText);
      }
      chancesUsed += 12;
    }
    lv_label_set_text(labelHours[row], hours);
    lv_label_set_text(labelChances[row], chances);
  }
}
