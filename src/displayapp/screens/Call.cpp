#include "displayapp/screens/Call.h"

#include <task.h>

#include "displayapp/DisplayApp.h"
#include "displayapp/InfiniTimeTheme.h"
#include "displayapp/screens/Symbols.h"
#include "components/ble/MusicService.h"
#include "components/datetime/DateTimeController.h"
#include "components/motor/MotorController.h"
#include "components/settings/Settings.h"

using namespace Pinetime::Applications::Screens;

namespace {
  // Layout of the 240x240 screen.
  //
  //   y   0.. 46  the time of day (left), where the main screen has it; the call's volume (right)
  //   y  46.. 72  what is going on: "Incoming call", "On a call", ...
  //   y  74..126  who it is, on up to two lines
  //   y 130..186  how long the call has been going on
  //   y 184..236  what a touch does
  constexpr lv_coord_t textWidth = 228;
  constexpr lv_coord_t timeOffsetX = 4;
  constexpr lv_coord_t timeOffsetY = -97;
  constexpr lv_coord_t stateOffsetY = -61;
  constexpr lv_coord_t callerTopY = 74;
  constexpr uint8_t callerLines = 2;
  constexpr lv_coord_t durationOffsetY = 38;
  constexpr lv_coord_t hintOffsetY = -4;

  constexpr lv_color_t callColor = LV_COLOR_MAKE(0x1e, 0xd7, 0x60);

  // Keeping the finger down this long declines a ringing call
  constexpr TickType_t holdDuration = pdMS_TO_TICKS(600);
  // Ending a call that is under way takes a longer hold, so it does not happen by accident
  constexpr TickType_t hangUpHoldDuration = pdMS_TO_TICKS(1000);
  // The screen came up by itself. A touch that was on its way to the screen before does not answer the call.
  constexpr TickType_t appearMute = pdMS_TO_TICKS(700);
  // If the phone has not reported the call answered or gone this long after being asked, show it ringing again
  constexpr TickType_t actionTimeout = pdMS_TO_TICKS(4000);

  // The buzz for a ringing call has no gaps: the motor is started again before it has run out.
  // It ends on its own soon after this screen stops asking for it.
  constexpr TickType_t buzzPeriod = pdMS_TO_TICKS(150);
  constexpr uint8_t buzzLengthMs = 250;
  // A call that rings longer than this is left to the phone
  constexpr TickType_t longestBuzz = pdMS_TO_TICKS(60000);

  constexpr uint8_t confirmBuzzMs = 15;
  constexpr TickType_t volumeHintDuration = pdMS_TO_TICKS(900);

  // Nothing is drawn for these
  constexpr uint8_t volumeNotDrawn = 0xfe;
  constexpr uint32_t secondsNotDrawn = 0xffffffff;
}

Call::Call(DisplayApp* app,
           Pinetime::Controllers::NotificationManager& notificationManager,
           Pinetime::Controllers::MotorController& motorController,
           Pinetime::Controllers::MusicService& musicService,
           Pinetime::Controllers::DateTime& dateTimeController,
           Pinetime::Controllers::Settings& settingsController,
           System::SystemTask& systemTask,
           bool ring)
  : app {app},
    notificationManager {notificationManager},
    motorController {motorController},
    musicService {musicService},
    dateTimeController {dateTimeController},
    settingsController {settingsController},
    wakeLock(systemTask) {

  // Top bar, left: the time of day, with AM/PM next to it in 12-hour mode, as on the main screen
  labelTime = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_font(labelTime, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &jetbrains_mono_42);
  lv_label_set_text_static(labelTime, "");
  lv_obj_align(labelTime, lv_scr_act(), LV_ALIGN_IN_LEFT_MID, timeOffsetX, timeOffsetY);

  labelAmPm = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelAmPm, "");
  lv_obj_align(labelAmPm, labelTime, LV_ALIGN_OUT_RIGHT_MID, 0, 6);

  // Top bar, right: the volume of the call, in orange and in the phone's own steps, like the
  // number on the main screen
  labelVolume = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelVolume, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::orange);
  lv_label_set_text_static(labelVolume, "");
  lv_obj_align(labelVolume, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, 0, 0);
  lv_obj_set_hidden(labelVolume, true);

  labelState = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelState, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, callColor);
  lv_label_set_text_static(labelState, "");
  lv_obj_align(labelState, lv_scr_act(), LV_ALIGN_CENTER, 0, stateOffsetY);

  // Who is calling. A long name wraps onto a second line, and ends in dots if that is not enough.
  labelCaller = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_long_mode(labelCaller, LV_LABEL_LONG_DOT);
  const lv_font_t* callerFont = lv_obj_get_style_text_font(labelCaller, LV_LABEL_PART_MAIN);
  const lv_coord_t callerLineSpace = lv_obj_get_style_text_line_space(labelCaller, LV_LABEL_PART_MAIN);
  lv_obj_set_size(labelCaller,
                  textWidth,
                  static_cast<lv_coord_t>(callerLines * lv_font_get_line_height(callerFont) + (callerLines - 1) * callerLineSpace));
  lv_label_set_align(labelCaller, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text(labelCaller, "");
  lv_obj_align(labelCaller, lv_scr_act(), LV_ALIGN_IN_TOP_MID, 0, callerTopY);

  // How long the call has been going on
  labelDuration = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_font(labelDuration, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &jetbrains_mono_42);
  lv_label_set_text_static(labelDuration, "");
  lv_obj_align(labelDuration, lv_scr_act(), LV_ALIGN_CENTER, 0, durationOffsetY);
  lv_obj_set_hidden(labelDuration, true);

  labelHint = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelHint, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_align(labelHint, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text_static(labelHint, "");
  lv_obj_align(labelHint, lv_scr_act(), LV_ALIGN_IN_BOTTOM_MID, 0, hintOffsetY);

  // A tap answers at once: there is no double tap to wait for here
  tapPad.Create(false, holdDuration);

  const TickType_t now = xTaskGetTickCount();
  if (ring && notificationManager.CallState() == CallStates::Ringing) {
    buzzing = true;
    buzzStartTick = now;
    lastBuzzTick = now - buzzPeriod;
    wakeLock.Lock();
    tapPad.Mute(now, appearMute);
  }

  taskRefresh = lv_task_create(RefreshTaskCallback, LV_DISP_DEF_REFR_PERIOD, LV_TASK_PRIO_MID, this);
  Refresh();
}

Call::~Call() {
  lv_task_del(taskRefresh);
  StopBuzz();
  lv_obj_clean(lv_scr_act());
}

void Call::StopBuzz() {
  if (buzzing) {
    buzzing = false;
    // Switches the motor off right away
    motorController.StopRinging();
    wakeLock.Release();
  }
}

void Call::Refresh() {
  const TickType_t now = xTaskGetTickCount();
  const auto tap = tapPad.Update(now);
  const uint32_t revision = notificationManager.Revision();
  const CallStates state = notificationManager.CallState();

  if (state == CallStates::Idle) {
    // The call is over, or was never there
    StopBuzz();
    running = false;
    return;
  }

  if ((state == CallStates::Ringing) == (action == Actions::Ending)) {
    // Answered, or ringing again: what was asked for belonged to the other state
    action = Actions::None;
  } else if (action != Actions::None && (now - actionTick) >= actionTimeout) {
    // The phone did not do it. The call is as it was.
    action = Actions::None;
  }
  tapPad.SetHoldDuration(state == CallStates::Ringing ? holdDuration : hangUpHoldDuration);

  if (state == CallStates::Ringing && action == Actions::None) {
    if (tap == Widgets::TapPad::Events::Tap) {
      musicService.event(Controllers::MusicService::EVENT_CALL_ANSWER);
      action = Actions::Answering;
      actionTick = now;
    } else if (tap == Widgets::TapPad::Events::Hold) {
      musicService.event(Controllers::MusicService::EVENT_CALL_DECLINE);
      action = Actions::Declining;
      actionTick = now;
    }
    if (action != Actions::None) {
      StopBuzz();
      motorController.RunForDuration(confirmBuzzMs);
    }
  } else if (state == CallStates::Active && action == Actions::None && tap == Widgets::TapPad::Events::Hold) {
    musicService.event(Controllers::MusicService::EVENT_CALL_HANGUP);
    action = Actions::Ending;
    actionTick = now;
    motorController.RunForDuration(confirmBuzzMs);
  }

  if (buzzing) {
    if (state != CallStates::Ringing || (now - buzzStartTick) >= longestBuzz) {
      StopBuzz();
    } else if ((now - lastBuzzTick) >= buzzPeriod) {
      lastBuzzTick = now;
      motorController.RunForDuration(buzzLengthMs);
    }
  }

  DrawClock();
  DrawDuration(state);
  DrawVolume(state, now);

  if (!drawn || state != shownState || action != shownAction || revision != shownRevision) {
    drawn = true;
    shownState = state;
    shownAction = action;
    shownRevision = revision;
    Draw(state);
  }
}

void Call::DrawClock() {
  uint8_t hour = dateTimeController.Hours();
  const uint8_t minute = dateTimeController.Minutes();
  if (hour == shownHour && minute == shownMinute) {
    return;
  }
  shownHour = hour;
  shownMinute = minute;

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

void Call::DrawDuration(CallStates state) {
  // Counted from when the call was answered. Nothing to count while it only rings.
  const bool timed = state == CallStates::Active && musicService.isCallTimed();
  const uint32_t seconds = timed ? musicService.getCallSeconds() : secondsNotDrawn;
  if (seconds == shownSeconds) {
    return;
  }
  shownSeconds = seconds;
  if (!timed) {
    lv_obj_set_hidden(labelDuration, true);
    return;
  }
  const int hours = static_cast<int>(seconds / 3600);
  const int minutes = static_cast<int>((seconds / 60) % 60);
  const int rest = static_cast<int>(seconds % 60);
  if (hours > 0) {
    lv_label_set_text_fmt(labelDuration, "%d:%02d:%02d", hours, minutes, rest);
  } else {
    lv_label_set_text_fmt(labelDuration, "%d:%02d", minutes, rest);
  }
  lv_obj_set_hidden(labelDuration, false);
  lv_obj_realign(labelDuration);
}

void Call::DrawVolume(CallStates state, TickType_t now) {
  using Music = Controllers::MusicService;

  if (volumeHintVisible && (now - volumeHintTick) >= volumeHintDuration) {
    volumeHintVisible = false;
    shownVolume = volumeNotDrawn;
  }

  // The number is the phone's own, so it also follows the phone's volume keys
  const uint8_t volume = state == CallStates::Active ? musicService.getCallVolume() : Music::StateUnknown;
  if (volume != Music::StateUnknown) {
    volumeHintVisible = false;
    if (volume != shownVolume) {
      shownVolume = volume;
      lv_label_set_text_fmt(labelVolume, "%d", volume);
      lv_obj_set_hidden(labelVolume, false);
      lv_obj_realign(labelVolume);
    }
  } else if (!volumeHintVisible && shownVolume != Music::StateUnknown) {
    shownVolume = Music::StateUnknown;
    lv_obj_set_hidden(labelVolume, true);
  }
}

void Call::Draw(CallStates state) {
  char caller[Pinetime::Controllers::NotificationManager::CallerSize + 1];
  notificationManager.CallerName(caller);
  lv_label_set_text(labelCaller, caller[0] != '\0' ? caller : "Unknown caller");
  lv_obj_realign(labelCaller);

  if (state == CallStates::Ringing) {
    if (action == Actions::Answering) {
      lv_label_set_text_static(labelState, "Answering...");
      lv_label_set_text_static(labelHint, "");
    } else if (action == Actions::Declining) {
      lv_label_set_text_static(labelState, "Declining...");
      lv_label_set_text_static(labelHint, "");
    } else {
      lv_label_set_text_static(labelState, "Incoming call");
      lv_label_set_text_static(labelHint, "Tap: answer\nHold: decline");
    }
  } else if (action == Actions::Ending) {
    lv_label_set_text_static(labelState, "Hanging up...");
    lv_label_set_text_static(labelHint, "");
  } else {
    lv_label_set_text_static(labelState, "On a call");
    lv_label_set_text_static(labelHint, "Swipe: call volume\nHold: hang up");
  }
  lv_obj_realign(labelState);
  lv_obj_realign(labelHint);
}

bool Call::OnTouchEvent(Pinetime::Applications::TouchEvents event) {
  switch (event) {
    case TouchEvents::SwipeLeft:
    case TouchEvents::SwipeRight:
      if (notificationManager.CallState() == CallStates::Ringing) {
        // Nothing to turn up or down yet. A sideways swipe stops the buzzing; the phone keeps ringing.
        StopBuzz();
      } else {
        const bool up = event == TouchEvents::SwipeRight;
        musicService.event(up ? Controllers::MusicService::EVENT_CALL_VOLUP : Controllers::MusicService::EVENT_CALL_VOLDOWN);
        motorController.RunForDuration(confirmBuzzMs);
        if (musicService.getCallVolume() == Controllers::MusicService::StateUnknown) {
          // The phone does not say the number. An arrow shows that the swipe was taken.
          lv_label_set_text_static(labelVolume, up ? Symbols::volumUp : Symbols::volumDown);
          lv_obj_set_hidden(labelVolume, false);
          lv_obj_realign(labelVolume);
          volumeHintVisible = true;
          volumeHintTick = xTaskGetTickCount();
          shownVolume = volumeNotDrawn;
        }
      }
      return true;
    case TouchEvents::SwipeDown:
      // The notifications are one further up
      app->StartApp(Apps::Notifications, DisplayApp::FullRefreshDirections::Down);
      return true;
    default:
      return false;
  }
}
