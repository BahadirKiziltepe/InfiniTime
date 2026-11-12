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
  // Vertical offsets of each row, measured from the centre of the 240x240 screen.
  // Rows are placed by their centre so the layout does not depend on a font's line spacing.
  constexpr lv_coord_t timeOffsetY = -63;
  constexpr lv_coord_t trackOffsetY = -17;
  constexpr lv_coord_t artistOffsetY = 9;
  constexpr lv_coord_t progressOffsetY = 31;

  constexpr lv_coord_t textWidth = 228;
  constexpr lv_coord_t progressWidth = 200;
  constexpr lv_coord_t progressHeight = 4;
  constexpr lv_coord_t buttonWidth = 76;
  constexpr lv_coord_t buttonHeight = 72;

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

  // Top row: new-notification marker, AM/PM, volume hint, bluetooth and battery
  statusIcons.Create();

  notificationIcon = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(notificationIcon, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, LV_COLOR_LIME);
  lv_label_set_text_static(notificationIcon, NotificationIcon::GetIcon(false));
  lv_obj_align(notificationIcon, nullptr, LV_ALIGN_IN_TOP_LEFT, 0, 0);

  labelAmPm = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelAmPm, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_text_static(labelAmPm, "");
  lv_obj_align(labelAmPm, nullptr, LV_ALIGN_IN_TOP_LEFT, 28, 0);

  labelVolume = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelVolume, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::orange);
  lv_label_set_text_static(labelVolume, Symbols::volumUp);
  lv_obj_align(labelVolume, nullptr, LV_ALIGN_IN_TOP_MID, 0, 0);
  lv_obj_set_hidden(labelVolume, true);

  // Clock
  labelTime = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_font(labelTime, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &jetbrains_mono_extrabold_compressed);
  lv_label_set_text_static(labelTime, "00:00");
  lv_obj_align(labelTime, lv_scr_act(), LV_ALIGN_CENTER, 0, timeOffsetY);

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

  // Previous / play-pause / next
  lv_style_init(&btnStyle);
  lv_style_set_radius(&btnStyle, LV_STATE_DEFAULT, 20);
  lv_style_set_bg_color(&btnStyle, LV_STATE_DEFAULT, Colors::bgAlt);

  lv_obj_t* label;

  btnPrev = lv_btn_create(lv_scr_act(), nullptr);
  btnPrev->user_data = this;
  lv_obj_set_event_cb(btnPrev, ButtonEventHandler);
  lv_obj_set_size(btnPrev, buttonWidth, buttonHeight);
  lv_obj_align(btnPrev, nullptr, LV_ALIGN_IN_BOTTOM_LEFT, 3, 0);
  lv_obj_add_style(btnPrev, LV_BTN_PART_MAIN, &btnStyle);
  label = lv_label_create(btnPrev, nullptr);
  lv_label_set_text_static(label, Symbols::stepBackward);

  btnNext = lv_btn_create(lv_scr_act(), nullptr);
  btnNext->user_data = this;
  lv_obj_set_event_cb(btnNext, ButtonEventHandler);
  lv_obj_set_size(btnNext, buttonWidth, buttonHeight);
  lv_obj_align(btnNext, nullptr, LV_ALIGN_IN_BOTTOM_RIGHT, -3, 0);
  lv_obj_add_style(btnNext, LV_BTN_PART_MAIN, &btnStyle);
  label = lv_label_create(btnNext, nullptr);
  lv_label_set_text_static(label, Symbols::stepForward);

  btnPlayPause = lv_btn_create(lv_scr_act(), nullptr);
  btnPlayPause->user_data = this;
  lv_obj_set_event_cb(btnPlayPause, ButtonEventHandler);
  lv_obj_set_size(btnPlayPause, buttonWidth, buttonHeight);
  lv_obj_align(btnPlayPause, nullptr, LV_ALIGN_IN_BOTTOM_MID, 0, 0);
  lv_obj_add_style(btnPlayPause, LV_BTN_PART_MAIN, &btnStyle);
  labelPlayPause = lv_label_create(btnPlayPause, nullptr);
  lv_label_set_text_static(labelPlayPause, Symbols::play);

  taskRefresh = lv_task_create(RefreshTaskCallback, LV_DISP_DEF_REFR_PERIOD, LV_TASK_PRIO_MID, this);
  Refresh();
}

WatchFaceRemote::~WatchFaceRemote() {
  lv_task_del(taskRefresh);
  lv_style_reset(&btnStyle);
  lv_obj_clean(lv_scr_act());
}

void WatchFaceRemote::Refresh() {
  statusIcons.Update();

  notificationState = notificationManager.AreNewNotificationsAvailable();
  if (notificationState.IsUpdated()) {
    lv_label_set_text_static(notificationIcon, NotificationIcon::GetIcon(notificationState.Get()));
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
