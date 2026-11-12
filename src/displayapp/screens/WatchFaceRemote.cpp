#include "displayapp/screens/WatchFaceRemote.h"

#include <lvgl/lvgl.h>
#include <task.h>
#include <cstdlib>

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
  //   y   0.. 46  top bar: time (left), battery level (right), markers under it
  //   y  66.. 90  track
  //   y  96..120  artist
  //   y 134..154  what a tap will do (play or pause), and the position in the track
  //   y 186..240  shuffle / add / repeat
  //
  // There are no playback buttons. Everything outside the bottom row is one touch pad:
  //   tap = play or pause, double tap = next track, tap and hold = previous track.
  //
  // Text rows are placed by their centre, as offsets from the centre of the screen,
  // so the layout does not depend on a font's line spacing.
  constexpr lv_coord_t timeOffsetX = 4;
  constexpr lv_coord_t timeOffsetY = -97;
  constexpr lv_coord_t trackOffsetY = -42;
  constexpr lv_coord_t artistOffsetY = -12;
  constexpr lv_coord_t playerOffsetY = 24;

  // Top bar, right: the battery icon (or the plug) sits in the corner, the number ends left of it
  constexpr lv_coord_t batteryIconSlot = 20;
  // Second line of the top bar, under the battery level
  constexpr lv_coord_t markerOffsetY = 24;

  constexpr lv_coord_t textWidth = 232;
  constexpr lv_coord_t playerIconOffsetX = 8;
  constexpr lv_coord_t progressOffsetX = -8;
  constexpr lv_coord_t progressWidth = 190;
  constexpr lv_coord_t progressHeight = 4;

  constexpr lv_coord_t buttonWidth = 76;
  constexpr lv_coord_t buttonMargin = 3;
  constexpr lv_coord_t extraButtonHeight = 54;

  // One swipe sends one volume event. The phone app moves the media volume a tenth of its range for it.
  constexpr uint8_t volumeStepsPerSwipe = 1;
  // Short buzz that confirms an action
  constexpr uint8_t buzzMs = 15;

  constexpr TickType_t musicPollPeriod = pdMS_TO_TICKS(500);
  constexpr TickType_t playOverrideDuration = pdMS_TO_TICKS(2500);
  constexpr TickType_t volumeHintDuration = pdMS_TO_TICKS(900);
  constexpr TickType_t skipHintDuration = pdMS_TO_TICKS(700);

  // A second tap makes a double tap if it starts within this time after the first one ended.
  // That is also how long a single tap waits before it is acted on.
  constexpr TickType_t doubleTapWindow = pdMS_TO_TICKS(300);
  // Keeping the finger down this long skips back
  constexpr TickType_t holdDuration = pdMS_TO_TICKS(500);
  // A finger that wanders further than this many pixels is neither tapping nor holding
  constexpr int tapSlack = 30;

  // The display was off if this screen did not run for this long
  constexpr TickType_t screenOffGap = pdMS_TO_TICKS(400);
  // The touch that woke the watch is not a command: touches are ignored for this long after waking up
  constexpr TickType_t wakeMuteDuration = pdMS_TO_TICKS(350);
  // After a tap on the plus: how long the pad under it ignores a second tap, and how long the plus
  // stays away while the phone has not confirmed yet
  constexpr TickType_t addMuteDuration = pdMS_TO_TICKS(700);
  constexpr TickType_t addOverrideDuration = pdMS_TO_TICKS(3000);
  // A press on the pad that follows this soon after a bottom button lost its press slid over from there
  constexpr TickType_t slideOverTime = pdMS_TO_TICKS(60);

  // Longest track for which the progress bar is drawn (keeps the maths in 32 bits)
  constexpr int maxTrackLength = 2000000;

  // Bottom row: dim while the phone has not said what the player's buttons show, light when the
  // function is off, green when it is on.
  constexpr lv_color_t extraUnknownColor = Colors::gray;
  constexpr lv_color_t extraOffColor = Colors::lightGray;
  constexpr lv_color_t extraOnColor = LV_COLOR_MAKE(0x1e, 0xd7, 0x60);

  // Smart shuffle: the shuffle arrows with a star. Repeat one song: the repeat arrows with a 1.
  constexpr const char* shuffleSmartText = "\xEF\x81\xB4\xEF\x80\x85";
  constexpr const char* repeatOneText = "\xEF\x81\xB9"
                                        "1";

  // Text the music service reports as the artist before the phone has sent anything
  constexpr const char* servicePlaceholderArtist = "Not Playing";

  void ButtonEventHandler(lv_obj_t* obj, lv_event_t event) {
    auto* screen = static_cast<WatchFaceRemote*>(obj->user_data);
    screen->OnButtonEvent(obj, event);
  }

  void TapAreaEventHandler(lv_obj_t* obj, lv_event_t event) {
    auto* screen = static_cast<WatchFaceRemote*>(obj->user_data);
    screen->OnTapAreaEvent(event);
  }

  // Where the finger is. Only meaningful while the screen is being touched.
  lv_point_t TouchPoint() {
    lv_point_t point = {0, 0};
    lv_indev_t* indev = lv_indev_get_next(nullptr);
    if (indev != nullptr) {
      lv_indev_get_point(indev, &point);
    }
    return point;
  }
}

WatchFaceRemote::WatchFaceRemote(Controllers::DateTime& dateTimeController,
                                 const Controllers::Battery& batteryController,
                                 const Controllers::Ble& bleController,
                                 Controllers::NotificationManager& notificationManager,
                                 Controllers::Settings& settingsController,
                                 Controllers::MotorController& motorController,
                                 Controllers::MusicService& musicService)
  : currentDateTime {{}},
    batteryIcon(true),
    dateTimeController {dateTimeController},
    batteryController {batteryController},
    bleController {bleController},
    notificationManager {notificationManager},
    settingsController {settingsController},
    motorController {motorController},
    musicService {musicService} {

  // Top bar, right: the battery level as a number, then the battery icon, or a plug while on the charger.
  // Under them: the new-notification marker and the volume hint.
  batteryIcon.Create(lv_scr_act());
  lv_obj_align(batteryIcon.GetObject(), lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -2, 1);

  labelPlug = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelPlug, Symbols::plug);
  lv_obj_align(labelPlug, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -1, 0);
  lv_obj_set_hidden(labelPlug, true);

  labelBattery = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelBattery, "");
  lv_obj_align(labelBattery, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -batteryIconSlot, 0);

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

  // What a tap will do: the pause sign while a song plays, the play sign while it does not.
  // After a skip it shows for a moment which way the skip went.
  labelPlayer = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelPlayer, Symbols::play);
  lv_obj_align(labelPlayer, lv_scr_act(), LV_ALIGN_IN_LEFT_MID, playerIconOffsetX, playerOffsetY);

  // Position in the track, next to it. Only shown when the phone reports the track's length.
  barProgress = lv_bar_create(lv_scr_act(), nullptr);
  lv_obj_set_size(barProgress, progressWidth, progressHeight);
  lv_obj_set_style_local_bg_color(barProgress, LV_BAR_PART_BG, LV_STATE_DEFAULT, Colors::bgAlt);
  lv_obj_set_style_local_bg_color(barProgress, LV_BAR_PART_INDIC, LV_STATE_DEFAULT, Colors::lightGray);
  lv_bar_set_range(barProgress, 0, 1000);
  lv_bar_set_value(barProgress, 0, LV_ANIM_OFF);
  lv_obj_align(barProgress, lv_scr_act(), LV_ALIGN_IN_RIGHT_MID, progressOffsetX, playerOffsetY);
  lv_obj_set_hidden(barProgress, true);

  // The touch pad: an invisible sheet over the whole screen. It is made after everything above and
  // before the bottom row, so it lies on top of all of it except those buttons.
  tapArea = lv_obj_create(lv_scr_act(), nullptr);
  lv_obj_set_size(tapArea, LV_HOR_RES, LV_VER_RES);
  lv_obj_set_pos(tapArea, 0, 0);
  lv_obj_set_style_local_bg_opa(tapArea, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, LV_OPA_TRANSP);
  lv_obj_set_style_local_border_width(tapArea, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, 0);
  tapArea->user_data = this;
  lv_obj_set_event_cb(tapArea, TapAreaEventHandler);

  // Bottom row: shuffle on the left, repeat on the right, and between them the plus, which is only
  // there while the current song is not saved.
  // The stock companion apps only understand play, pause, next, previous and volume. These three
  // are acted on by the Watch Helper phone app, which also reports what each one currently shows.
  lv_style_init(&extraBtnStyle);
  lv_style_set_radius(&extraBtnStyle, LV_STATE_DEFAULT, 16);
  lv_style_set_bg_color(&extraBtnStyle, LV_STATE_DEFAULT, Colors::bgDark);

  auto makeExtraButton = [this](lv_align_t align, lv_coord_t offsetX, lv_obj_t*& icon) {
    lv_obj_t* button = lv_btn_create(lv_scr_act(), nullptr);
    lv_obj_set_size(button, buttonWidth, extraButtonHeight);
    lv_obj_align(button, nullptr, align, offsetX, 0);
    lv_obj_add_style(button, LV_BTN_PART_MAIN, &extraBtnStyle);
    button->user_data = this;
    lv_obj_set_event_cb(button, ButtonEventHandler);
    icon = lv_label_create(button, nullptr);
    return button;
  };
  btnShuffle = makeExtraButton(LV_ALIGN_IN_BOTTOM_LEFT, buttonMargin, labelShuffle);
  btnAdd = makeExtraButton(LV_ALIGN_IN_BOTTOM_MID, 0, labelAdd);
  btnRepeat = makeExtraButton(LV_ALIGN_IN_BOTTOM_RIGHT, -buttonMargin, labelRepeat);
  lv_obj_set_style_local_text_color(labelAdd, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, extraOffColor);
  lv_label_set_text_static(labelAdd, Symbols::plus);
  lv_obj_set_hidden(btnAdd, true);

  lastActiveTick = xTaskGetTickCount();
  taskRefresh = lv_task_create(RefreshTaskCallback, LV_DISP_DEF_REFR_PERIOD, LV_TASK_PRIO_MID, this);
  Refresh();
}

WatchFaceRemote::~WatchFaceRemote() {
  lv_task_del(taskRefresh);
  lv_style_reset(&extraBtnStyle);
  lv_obj_clean(lv_scr_act());
}

void WatchFaceRemote::Refresh() {
  const TickType_t now = xTaskGetTickCount();
  NoteScreenOn(now);
  UpdateTaps(now);
  UpdateBattery();

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

  if (volumeHintVisible && (now - volumeHintTick) >= volumeHintDuration) {
    lv_obj_set_hidden(labelVolume, true);
    volumeHintVisible = false;
  }

  if (skipHintVisible && (now - skipHintTick) >= skipHintDuration) {
    skipHintVisible = false;
    ShowPlayerIcon();
  }

  if (!musicPolled || (now - lastMusicPoll) >= musicPollPeriod) {
    musicPolled = true;
    lastMusicPoll = now;
    UpdateMusic(now);
  }
}

void WatchFaceRemote::UpdateBattery() {
  batteryPercent = batteryController.PercentRemaining();
  if (batteryPercent.IsUpdated()) {
    const uint8_t percent = batteryPercent.Get();
    batteryIcon.SetBatteryPercentage(percent);
    lv_label_set_text_fmt(labelBattery, "%d%%", percent);
    lv_obj_realign(labelBattery);
  }

  powerPresent = batteryController.IsPowerPresent();
  if (powerPresent.IsUpdated()) {
    const bool plugged = powerPresent.Get();
    lv_obj_set_hidden(batteryIcon.GetObject(), plugged);
    lv_obj_set_hidden(labelPlug, !plugged);
  }
}

void WatchFaceRemote::NoteScreenOn(TickType_t now) {
  if ((now - lastActiveTick) >= screenOffGap) {
    // Just woke up. Whatever was going on with the touch pad before is over.
    woke = true;
    wokeTick = now;
    fingerDown = false;
    tapWaiting = false;
  }
  lastActiveTick = now;
}

bool WatchFaceRemote::JustWoke(TickType_t now) {
  if (woke && (now - wokeTick) >= wakeMuteDuration) {
    woke = false;
  }
  return woke;
}

void WatchFaceRemote::OnTapAreaEvent(lv_event_t event) {
  const TickType_t now = xTaskGetTickCount();
  NoteScreenOn(now);

  switch (event) {
    case LV_EVENT_PRESSED: {
      fingerDown = true;
      pressTick = now;
      pressPoint = TouchPoint();

      if (padMuted && (now - padMuteTick) >= addMuteDuration) {
        padMuted = false;
      }
      const bool slidOver = slidOffButton && (now - slidOffTick) < slideOverTime;
      slidOffButton = false;
      // A press that cannot become a command: the watch just woke up, the plus just vanished from
      // under the finger, or the finger came over from one of the buttons.
      pressSpent = JustWoke(now) || padMuted || slidOver;
      break;
    }
    case LV_EVENT_RELEASED:
      if (!fingerDown) {
        break;
      }
      fingerDown = false;
      if (pressSpent) {
        break;
      }
      if (tapWaiting) {
        // Second tap in a row
        tapWaiting = false;
        Skip(true, now);
      } else {
        // First tap. UpdateTaps acts on it once it is clear that no second one follows.
        tapWaiting = true;
        tapTick = now;
      }
      break;
    case LV_EVENT_PRESS_LOST:
      // The press turned into a swipe or slid onto a button
      fingerDown = false;
      break;
    default:
      break;
  }
}

void WatchFaceRemote::UpdateTaps(TickType_t now) {
  if (fingerDown && !pressSpent) {
    const lv_point_t point = TouchPoint();
    if (std::abs(point.x - pressPoint.x) > tapSlack || std::abs(point.y - pressPoint.y) > tapSlack) {
      // The finger is wandering: this is neither a tap nor a hold
      pressSpent = true;
    } else if ((now - pressTick) >= holdDuration) {
      pressSpent = true;
      tapWaiting = false;
      Skip(false, now);
    }
  }

  if (tapWaiting && !fingerDown && (now - tapTick) >= doubleTapWindow) {
    tapWaiting = false;
    TogglePlay(now);
  }
}

void WatchFaceRemote::TogglePlay(TickType_t now) {
  if (!bleController.IsConnected()) {
    return;
  }
  // The phone decides from the player's real state whether this starts or stops the music
  musicService.event(playing ? Controllers::MusicService::EVENT_MUSIC_PAUSE : Controllers::MusicService::EVENT_MUSIC_PLAY);
  playing = !playing;
  playOverride = true;
  playOverrideTick = now;
  skipHintVisible = false;
  ShowPlayerIcon();
  motorController.RunForDuration(buzzMs);
}

void WatchFaceRemote::Skip(bool forward, TickType_t now) {
  if (!bleController.IsConnected()) {
    return;
  }
  musicService.event(forward ? Controllers::MusicService::EVENT_MUSIC_NEXT : Controllers::MusicService::EVENT_MUSIC_PREV);
  skipHintVisible = true;
  skipHintForward = forward;
  skipHintTick = now;
  ShowPlayerIcon();
  motorController.RunForDuration(buzzMs);
}

void WatchFaceRemote::ShowPlayerIcon() {
  const char* icon = playing ? Symbols::pause : Symbols::play;
  if (skipHintVisible) {
    icon = skipHintForward ? Symbols::stepForward : Symbols::stepBackward;
  }
  lv_label_set_text_static(labelPlayer, icon);
  lv_obj_realign(labelPlayer);
}

void WatchFaceRemote::UpdateMusic(TickType_t now) {
  // The Bluetooth icon is gone from the top bar. Without the phone nothing here works, so say that
  // in place of the song instead of leaving the last one up.
  const bool connected = bleController.IsConnected();

  std::string newTrack = connected ? musicService.getTrack() : std::string {};
  if (!trackDrawn || connected != shownConnected || newTrack != track) {
    trackDrawn = true;
    shownConnected = connected;
    track = std::move(newTrack);
    if (!connected) {
      lv_label_set_text_static(labelTrack, "Phone not connected");
    } else if (track.empty()) {
      lv_label_set_text_static(labelTrack, "Not playing");
    } else {
      lv_label_set_text(labelTrack, track.c_str());
    }
    lv_obj_set_hidden(labelPlayer, !connected);
  }

  std::string newArtist = connected ? musicService.getArtist() : std::string {};
  if (newArtist == servicePlaceholderArtist) {
    newArtist.clear();
  }
  if (newArtist != artist) {
    artist = std::move(newArtist);
    lv_label_set_text(labelArtist, artist.c_str());
  }

  const bool servicePlaying = connected && musicService.isPlaying();
  if (playOverride && (servicePlaying == playing || (now - playOverrideTick) >= playOverrideDuration)) {
    playOverride = false;
  }
  if (!playOverride && servicePlaying != playing) {
    playing = servicePlaying;
    ShowPlayerIcon();
  }

  const int length = connected ? musicService.getTrackLength() : 0;
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

  UpdateExtraButtons(now);
}

void WatchFaceRemote::UpdateExtraButtons(TickType_t now) {
  using Music = Controllers::MusicService;

  // Whatever the phone said last is only worth showing while the phone is still there.
  const bool connected = bleController.IsConnected();
  const uint8_t shuffle = connected ? musicService.getShuffleState() : Music::StateUnknown;
  const uint8_t repeat = connected ? musicService.getRepeatState() : Music::StateUnknown;
  const uint8_t saved = connected ? musicService.getSavedState() : Music::StateUnknown;

  if (shuffle != shownShuffle) {
    shownShuffle = shuffle;
    const bool on = shuffle == Music::ShuffleOn || shuffle == Music::ShuffleSmart;
    const lv_color_t color = on ? extraOnColor : (shuffle == Music::ShuffleOff ? extraOffColor : extraUnknownColor);
    lv_obj_set_style_local_text_color(labelShuffle, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, color);
    lv_label_set_text_static(labelShuffle, shuffle == Music::ShuffleSmart ? shuffleSmartText : Symbols::shuffle);
  }

  if (repeat != shownRepeat) {
    shownRepeat = repeat;
    const bool on = repeat == Music::RepeatAll || repeat == Music::RepeatOne;
    const lv_color_t color = on ? extraOnColor : (repeat == Music::RepeatOff ? extraOffColor : extraUnknownColor);
    lv_obj_set_style_local_text_color(labelRepeat, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, color);
    lv_label_set_text_static(labelRepeat, repeat == Music::RepeatOne ? repeatOneText : Symbols::repeat);
  }

  // The plus is only there while the phone says the current song is not saved. After a tap it goes
  // away at once; if the phone still says "not saved" a few seconds later, it comes back.
  bool addVisible = saved == Music::SavedNo;
  if (addOverride) {
    if (!addVisible || (now - addOverrideTick) >= addOverrideDuration) {
      addOverride = false;
    } else {
      addVisible = false;
    }
  }
  if (addVisible != shownAdd) {
    shownAdd = addVisible;
    lv_obj_set_hidden(btnAdd, !addVisible);
  }
}

void WatchFaceRemote::SendExtra(char event) {
  if (!bleController.IsConnected()) {
    return;
  }
  musicService.event(event);
  motorController.RunForDuration(buzzMs);
}

void WatchFaceRemote::OnButtonEvent(lv_obj_t* obj, lv_event_t event) {
  const TickType_t now = xTaskGetTickCount();
  NoteScreenOn(now);

  if (event == LV_EVENT_PRESS_LOST) {
    // If the pad is pressed right after this, the finger slid over from this button
    slidOffButton = true;
    slidOffTick = now;
    return;
  }
  if (event != LV_EVENT_CLICKED || JustWoke(now)) {
    return;
  }

  if (obj == btnShuffle) {
    SendExtra(Controllers::MusicService::EVENT_MUSIC_SHUFFLE);
  } else if (obj == btnRepeat) {
    SendExtra(Controllers::MusicService::EVENT_MUSIC_REPEAT);
  } else if (obj == btnAdd) {
    if (!bleController.IsConnected()) {
      return;
    }
    SendExtra(Controllers::MusicService::EVENT_MUSIC_SAVE);
    addOverride = true;
    addOverrideTick = now;
    shownAdd = false;
    lv_obj_set_hidden(btnAdd, true);
    // A second tap on the same spot now lands on the pad. It is not meant as play/pause.
    padMuted = true;
    padMuteTick = now;
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
    motorController.RunForDuration(buzzMs);
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
