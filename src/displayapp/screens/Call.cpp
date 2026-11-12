#include "displayapp/screens/Call.h"

#include <task.h>

#include "displayapp/DisplayApp.h"
#include "displayapp/InfiniTimeTheme.h"
#include "displayapp/screens/Symbols.h"
#include "components/ble/MusicService.h"
#include "components/motor/MotorController.h"

using namespace Pinetime::Applications::Screens;

namespace {
  constexpr lv_coord_t textWidth = 228;
  constexpr lv_coord_t stateOffsetY = -84;
  constexpr lv_coord_t callerOffsetY = -18;
  constexpr lv_coord_t hintOffsetY = -6;

  constexpr lv_color_t callColor = LV_COLOR_MAKE(0x1e, 0xd7, 0x60);

  // Keeping the finger down this long declines the call
  constexpr TickType_t holdDuration = pdMS_TO_TICKS(600);
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
}

Call::Call(DisplayApp* app,
           Pinetime::Controllers::NotificationManager& notificationManager,
           Pinetime::Controllers::MotorController& motorController,
           Pinetime::Controllers::MusicService& musicService,
           System::SystemTask& systemTask,
           bool ring)
  : app {app},
    notificationManager {notificationManager},
    motorController {motorController},
    musicService {musicService},
    wakeLock(systemTask) {

  labelState = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelState, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, callColor);
  lv_label_set_text_static(labelState, "");
  lv_obj_align(labelState, lv_scr_act(), LV_ALIGN_CENTER, 0, stateOffsetY);

  // Who is calling. A long name wraps onto more lines.
  labelCaller = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_long_mode(labelCaller, LV_LABEL_LONG_BREAK);
  lv_obj_set_width(labelCaller, textWidth);
  lv_label_set_align(labelCaller, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text(labelCaller, "");
  lv_obj_align(labelCaller, lv_scr_act(), LV_ALIGN_CENTER, 0, callerOffsetY);

  labelHint = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelHint, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_align(labelHint, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text_static(labelHint, "");
  lv_obj_align(labelHint, lv_scr_act(), LV_ALIGN_IN_BOTTOM_MID, 0, hintOffsetY);

  labelVolume = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelVolume, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::orange);
  lv_label_set_text_static(labelVolume, Symbols::volumUp);
  lv_obj_align(labelVolume, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -4, 4);
  lv_obj_set_hidden(labelVolume, true);

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

  if (state != CallStates::Ringing) {
    action = Actions::None;
  } else if (action != Actions::None && (now - actionTick) >= actionTimeout) {
    // The phone did not do it. The call is still ringing.
    action = Actions::None;
  }

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
  }

  if (buzzing) {
    if (state != CallStates::Ringing || (now - buzzStartTick) >= longestBuzz) {
      StopBuzz();
    } else if ((now - lastBuzzTick) >= buzzPeriod) {
      lastBuzzTick = now;
      motorController.RunForDuration(buzzLengthMs);
    }
  }

  if (volumeHintVisible && (now - volumeHintTick) >= volumeHintDuration) {
    lv_obj_set_hidden(labelVolume, true);
    volumeHintVisible = false;
  }

  if (!drawn || state != shownState || action != shownAction || revision != shownRevision) {
    drawn = true;
    shownState = state;
    shownAction = action;
    shownRevision = revision;
    Draw(state);
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
  } else {
    lv_label_set_text_static(labelState, "On a call");
    lv_label_set_text_static(labelHint, "Swipe left or right\nfor call volume");
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
        lv_label_set_text_static(labelVolume, up ? Symbols::volumUp : Symbols::volumDown);
        lv_obj_set_hidden(labelVolume, false);
        lv_obj_realign(labelVolume);
        volumeHintVisible = true;
        volumeHintTick = xTaskGetTickCount();
      }
      return true;
    case TouchEvents::SwipeUp:
      // The notifications are one further down
      app->StartApp(Apps::Notifications, DisplayApp::FullRefreshDirections::Up);
      return true;
    default:
      return false;
  }
}
