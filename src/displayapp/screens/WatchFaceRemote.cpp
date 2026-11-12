#include "displayapp/screens/WatchFaceRemote.h"

#include <lvgl/lvgl.h>
#include <task.h>

#include "displayapp/InfiniTimeTheme.h"
#include "displayapp/screens/NotificationIcon.h"
#include "displayapp/screens/Symbols.h"
#include "components/battery/BatteryController.h"
#include "components/ble/BleController.h"
#include "components/ble/MusicService.h"
#include "components/ble/NotificationManager.h"
#include "components/motor/MotorController.h"
#include "components/settings/Settings.h"

using namespace Pinetime::Applications::Screens;

namespace {
  // Layout of the 240x240 screen.
  //
  //   y   0.. 46  top bar: time (left), bluetooth + battery (right), markers under them
  //   y  50.. 78  track
  //   y  76..104  artist
  //   y 110..114  progress bar
  //   y 124..186  previous / play-pause / next
  //   y 190..240  shuffle / repeat / add
  //
  // Text rows are placed by their centre, as offsets from the centre of the screen,
  // so the layout does not depend on a font's line spacing.
  constexpr lv_coord_t timeOffsetX = 4;
  constexpr lv_coord_t timeOffsetY = -97;
  constexpr lv_coord_t trackOffsetY = -56;
  constexpr lv_coord_t artistOffsetY = -30;
  constexpr lv_coord_t progressOffsetY = -8;

  // Second line of the top bar, under the bluetooth and battery icons
  constexpr lv_coord_t markerOffsetY = 24;

  constexpr lv_coord_t textWidth = 232;
  constexpr lv_coord_t progressWidth = 224;
  constexpr lv_coord_t progressHeight = 4;

  constexpr lv_coord_t buttonWidth = 76;
  constexpr lv_coord_t buttonMargin = 3;
  constexpr lv_coord_t mainButtonHeight = 62;
  constexpr lv_coord_t extraButtonHeight = 50;
  constexpr lv_coord_t rowGap = 4;
  constexpr lv_coord_t mainRowOffsetY = -(extraButtonHeight + rowGap);

  // One swipe moves the phone's media volume by this many steps
  constexpr uint8_t volumeStepsPerSwipe = 3;
  constexpr uint8_t volumeBuzzMs = 15;

  constexpr TickType_t musicPollPeriod = pdMS_TO_TICKS(500);
  constexpr TickType_t playOverrideDuration = pdMS_TO_TICKS(2500);
  constexpr TickType_t volumeHintDuration = pdMS_TO_TICKS(900);

  // Longest track for which the progress bar is drawn (keeps the maths in 32 bits)
  constexpr int maxTrackLength = 2000000;

  // Text the music service reports as the artist before the phone has sent anything
  constexpr const char* servicePlaceholderArtist = "Not Playing";

  void ButtonEventHandler(lv_obj_t* obj, lv_event_t event) {
    auto* screen = static_cast<WatchFaceRemote*>(obj->user_data);
    screen->OnButtonEvent(obj, event);
  }

  lv_obj_t* MakeButton(lv_style_t* style, lv_coord_t height, lv_align_t align, lv_coord_t offsetX, lv_coord_t offsetY) {
    lv_obj_t* button = lv_btn_create(lv_scr_act(), nullptr);
    lv_obj_set_size(button, buttonWidth, height);
    lv_obj_align(button, nullptr, align, offsetX, offsetY);
    lv_obj_add_style(button, LV_BTN_PART_MAIN, style);
    return button;
  }
}

WatchFaceRemote::WatchFaceRemote(Controllers::DateTime& dateTimeController,
                                 const Controllers::Battery& batteryController,
                                 const Controllers::Ble& bleController,
                                 const Controllers::AlarmController& alarmController,
                                 Controllers::NotificationManager& notificationManager,
                                 Controllers::Settings& settingsController,
                                 Controllers::MotorController& motorController,
                                 Controllers::MusicService& musicService)
  : currentDateTime {{}},
    dateTimeController {dateTimeController},
    bleController {bleController},
    notificationManager {notificationManager},
    settingsController {settingsController},
    motorController {motorController},
    musicService {musicService},
    statusIcons(batteryController, bleController, alarmController) {

  // Top bar, right: bluetooth and battery, and under them the new-notification marker and the volume hint
  statusIcons.Create();

  notificationIcon = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(notificationIcon, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, LV_COLOR_LIME);
  lv_label_set_text_static(notificationIcon, NotificationIcon::GetIcon(false));
  lv_obj_align(notificationIcon, nullptr, LV_ALIGN_IN_TOP_RIGHT, -30, markerOffsetY);

  labelVolume = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelVolume, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::orange);
  lv_label_set_text_static(labelVolume, Symbols::volumUp);
  lv_obj_align(labelVolume, nullptr, LV_ALIGN_IN_TOP_RIGHT, 0, markerOffsetY);
  lv_obj_set_hidden(labelVolume, true);

  // Top bar, left: the time, with AM/PM next to it in 12-hour mode
  labelTime = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_font(labelTime, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &jetbrains_mono_42);
  lv_label_set_text_static(labelTime, "00:00");
  lv_obj_align(labelTime, lv_scr_act(), LV_ALIGN_IN_LEFT_MID, timeOffsetX, timeOffsetY);

  labelAmPm = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelAmPm, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_text_static(labelAmPm, "");
  lv_obj_align(labelAmPm, labelTime, LV_ALIGN_OUT_RIGHT_MID, 6, 6);

  // Track and artist. Text that does not fit scrolls.
  labelTrack = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_long_mode(labelTrack, LV_LABEL_LONG_SROLL_CIRC);
  lv_obj_set_width(labelTrack, textWidth);
  lv_label_set_align(labelTrack, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text_static(labelTrack, "Not playing");
  lv_obj_align(labelTrack, lv_scr_act(), LV_ALIGN_CENTER, 0, trackOffsetY);

  labelArtist = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelArtist, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_long_mode(labelArtist, LV_LABEL_LONG_SROLL_CIRC);
  lv_obj_set_width(labelArtist, textWidth);
  lv_label_set_align(labelArtist, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text_static(labelArtist, "");
  lv_obj_align(labelArtist, lv_scr_act(), LV_ALIGN_CENTER, 0, artistOffsetY);

  // Position in the track. Only shown when the phone reports the track's length.
  barProgress = lv_bar_create(lv_scr_act(), nullptr);
  lv_obj_set_size(barProgress, progressWidth, progressHeight);
  lv_obj_set_style_local_bg_color(barProgress, LV_BAR_PART_BG, LV_STATE_DEFAULT, Colors::bgAlt);
  lv_obj_set_style_local_bg_color(barProgress, LV_BAR_PART_INDIC, LV_STATE_DEFAULT, Colors::lightGray);
  lv_bar_set_range(barProgress, 0, 1000);
  lv_bar_set_value(barProgress, 0, LV_ANIM_OFF);
  lv_obj_align(barProgress, lv_scr_act(), LV_ALIGN_CENTER, 0, progressOffsetY);
  lv_obj_set_hidden(barProgress, true);

  // First row of buttons: previous / play-pause / next
  lv_style_init(&btnStyle);
  lv_style_set_radius(&btnStyle, LV_STATE_DEFAULT, 20);
  lv_style_set_bg_color(&btnStyle, LV_STATE_DEFAULT, Colors::bgAlt);

  btnPrev = MakeButton(&btnStyle, mainButtonHeight, LV_ALIGN_IN_BOTTOM_LEFT, buttonMargin, mainRowOffsetY);
  btnPrev->user_data = this;
  lv_obj_set_event_cb(btnPrev, ButtonEventHandler);
  lv_label_set_text_static(lv_label_create(btnPrev, nullptr), Symbols::stepBackward);

  btnPlayPause = MakeButton(&btnStyle, mainButtonHeight, LV_ALIGN_IN_BOTTOM_MID, 0, mainRowOffsetY);
  btnPlayPause->user_data = this;
  lv_obj_set_event_cb(btnPlayPause, ButtonEventHandler);
  labelPlayPause = lv_label_create(btnPlayPause, nullptr);
  lv_label_set_text_static(labelPlayPause, Symbols::play);

  btnNext = MakeButton(&btnStyle, mainButtonHeight, LV_ALIGN_IN_BOTTOM_RIGHT, -buttonMargin, mainRowOffsetY);
  btnNext->user_data = this;
  lv_obj_set_event_cb(btnNext, ButtonEventHandler);
  lv_label_set_text_static(lv_label_create(btnNext, nullptr), Symbols::stepForward);

  // Second row: shuffle / repeat / add.
  // The stock companion apps only understand play, pause, next, previous and volume, so these
  // need a phone-side helper. Until it exists they are drawn dimmed and do not react to taps.
  lv_style_init(&extraBtnStyle);
  lv_style_set_radius(&extraBtnStyle, LV_STATE_DEFAULT, 16);
  lv_style_set_bg_color(&extraBtnStyle, LV_STATE_DEFAULT, Colors::bgDark);

  auto makeExtraButton = [this](lv_align_t align, lv_coord_t offsetX, const char* symbol) {
    lv_obj_t* button = MakeButton(&extraBtnStyle, extraButtonHeight, align, offsetX, 0);
    lv_obj_set_click(button, false);
    lv_obj_t* icon = lv_label_create(button, nullptr);
    lv_obj_set_style_local_text_color(icon, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::gray);
    lv_label_set_text_static(icon, symbol);
    return button;
  };
  btnShuffle = makeExtraButton(LV_ALIGN_IN_BOTTOM_LEFT, buttonMargin, Symbols::shuffle);
  btnRepeat = makeExtraButton(LV_ALIGN_IN_BOTTOM_MID, 0, Symbols::repeat);
  btnAdd = makeExtraButton(LV_ALIGN_IN_BOTTOM_RIGHT, -buttonMargin, Symbols::plus);

  taskRefresh = lv_task_create(RefreshTaskCallback, LV_DISP_DEF_REFR_PERIOD, LV_TASK_PRIO_MID, this);
  Refresh();
}

WatchFaceRemote::~WatchFaceRemote() {
  lv_task_del(taskRefresh);
  lv_style_reset(&btnStyle);
  lv_style_reset(&extraBtnStyle);
  lv_obj_clean(lv_scr_act());
}

void WatchFaceRemote::Refresh() {
  statusIcons.Update();

  notificationState = notificationManager.AreNewNotificationsAvailable();
  if (notificationState.IsUpdated()) {
    lv_label_set_text_static(notificationIcon, NotificationIcon::GetIcon(notificationState.Get()));
    lv_obj_realign(notificationIcon);
  }

  currentDateTime = std::chrono::time_point_cast<std::chrono::minutes>(dateTimeController.CurrentDateTime());
  if (currentDateTime.IsUpdated()) {
    uint8_t hour = dateTimeController.Hours();
    uint8_t minute = dateTimeController.Minutes();

    if (settingsController.GetClockType() == Controllers::Settings::ClockType::H12) {
      lv_label_set_text_static(labelAmPm, (hour >= 12) ? "PM" : "AM");
      if (hour == 0) {
        hour = 12;
      } else if (hour > 12) {
        hour = hour - 12;
      }
      lv_label_set_text_fmt(labelTime, "%d:%02d", hour, minute);
    } else {
      lv_label_set_text_static(labelAmPm, "");
      lv_label_set_text_fmt(labelTime, "%02d:%02d", hour, minute);
    }
    lv_obj_realign(labelTime);
    lv_obj_realign(labelAmPm);
  }

  const TickType_t now = xTaskGetTickCount();

  if (volumeHintVisible && (now - volumeHintTick) >= volumeHintDuration) {
    lv_obj_set_hidden(labelVolume, true);
    volumeHintVisible = false;
  }

  if (!musicPolled || (now - lastMusicPoll) >= musicPollPeriod) {
    musicPolled = true;
    lastMusicPoll = now;
    UpdateMusic(now);
  }
}

void WatchFaceRemote::UpdateMusic(TickType_t now) {
  std::string newTrack = musicService.getTrack();
  if (newTrack != track) {
    track = std::move(newTrack);
    if (track.empty()) {
      lv_label_set_text_static(labelTrack, "Not playing");
    } else {
      lv_label_set_text(labelTrack, track.c_str());
    }
  }

  std::string newArtist = musicService.getArtist();
  if (newArtist != artist) {
    artist = std::move(newArtist);
    if (artist == servicePlaceholderArtist) {
      lv_label_set_text_static(labelArtist, "");
    } else {
      lv_label_set_text(labelArtist, artist.c_str());
    }
  }

  const bool servicePlaying = musicService.isPlaying();
  if (playOverride && (servicePlaying == playing || (now - playOverrideTick) >= playOverrideDuration)) {
    playOverride = false;
  }
  if (!playOverride && servicePlaying != playing) {
    SetPlaying(servicePlaying);
  }

  const int length = musicService.getTrackLength();
  if (length > 0 && length <= maxTrackLength) {
    int position = musicService.getProgress();
    if (position < 0) {
      position = 0;
    } else if (position > length) {
      position = length;
    }
    lv_bar_set_value(barProgress, static_cast<int16_t>((position * 1000) / length), LV_ANIM_OFF);
    if (!progressVisible) {
      lv_obj_set_hidden(barProgress, false);
      progressVisible = true;
    }
  } else if (progressVisible) {
    lv_obj_set_hidden(barProgress, true);
    progressVisible = false;
  }
}

void WatchFaceRemote::SetPlaying(bool isPlaying) {
  playing = isPlaying;
  lv_label_set_text_static(labelPlayPause, isPlaying ? Symbols::pause : Symbols::play);
}

void WatchFaceRemote::OnButtonEvent(lv_obj_t* obj, lv_event_t event) {
  if (event != LV_EVENT_CLICKED) {
    return;
  }

  if (obj == btnPrev) {
    musicService.event(Controllers::MusicService::EVENT_MUSIC_PREV);
  } else if (obj == btnNext) {
    musicService.event(Controllers::MusicService::EVENT_MUSIC_NEXT);
  } else if (obj == btnPlayPause) {
    if (playing) {
      musicService.event(Controllers::MusicService::EVENT_MUSIC_PAUSE);
    } else {
      musicService.event(Controllers::MusicService::EVENT_MUSIC_PLAY);
    }
    if (bleController.IsConnected()) {
      SetPlaying(!playing);
      playOverride = true;
      playOverrideTick = xTaskGetTickCount();
    }
  }
}

void WatchFaceRemote::ChangeVolume(bool up) {
  if (bleController.IsConnected()) {
    for (uint8_t i = 0; i < volumeStepsPerSwipe; i++) {
      if (up) {
        musicService.event(Controllers::MusicService::EVENT_MUSIC_VOLUP);
      } else {
        musicService.event(Controllers::MusicService::EVENT_MUSIC_VOLDOWN);
      }
    }
    motorController.RunForDuration(volumeBuzzMs);
  }

  lv_label_set_text_static(labelVolume, up ? Symbols::volumUp : Symbols::volumDown);
  lv_obj_set_hidden(labelVolume, false);
  lv_obj_realign(labelVolume);
  volumeHintVisible = true;
  volumeHintTick = xTaskGetTickCount();
}

bool WatchFaceRemote::OnTouchEvent(Pinetime::Applications::TouchEvents event) {
  switch (event) {
    case TouchEvents::SwipeLeft:
      ChangeVolume(false);
      return true;
    case TouchEvents::SwipeRight:
      ChangeVolume(true);
      return true;
    default:
      return false;
  }
}
