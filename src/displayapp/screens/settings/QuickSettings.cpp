#include "displayapp/screens/settings/QuickSettings.h"
#include <task.h>
#include "displayapp/DisplayApp.h"
#include "displayapp/screens/Symbols.h"
#include "displayapp/screens/BatteryIcon.h"
#include "components/ble/BleController.h"
#include "components/ble/MusicService.h"
#include "displayapp/InfiniTimeTheme.h"

using namespace Pinetime::Applications::Screens;

namespace {
  using Music = Pinetime::Controllers::MusicService;

  // The two phone buttons follow the phone four times a second
  constexpr uint32_t refreshPeriodMs = 250;
  // The time and the icons next to it every five seconds, as before
  constexpr uint8_t clockRefreshes = 20;

  // How long a button shows what the phone was asked for before it falls back to what the phone
  // reports. Changing the sound mode can take the phone a moment longer than starting to ring.
  constexpr TickType_t soundPatience = pdMS_TO_TICKS(6000);
  constexpr TickType_t findPatience = pdMS_TO_TICKS(4000);

  constexpr uint8_t tapBuzzMs = 35;

  // What the find-my-phone button can show
  constexpr uint8_t findIdle = 0;
  constexpr uint8_t findRinging = 1;
  constexpr uint8_t findNoPhone = 2;

  constexpr lv_color_t vibrateColor = LV_COLOR_MAKE(0x00, 0x50, 0xff);
  constexpr lv_color_t muteColor = LV_COLOR_MAKE(0xff, 0x00, 0x00);
  constexpr lv_color_t ringingColor = LV_COLOR_MAKE(0xff, 0xb0, 0x00);
  constexpr lv_color_t offColor = LV_COLOR_MAKE(0x80, 0x80, 0x80);

  void ButtonEventHandler(lv_obj_t* obj, lv_event_t event) {
    auto* screen = static_cast<QuickSettings*>(obj->user_data);
    if (event == LV_EVENT_CLICKED) {
      screen->OnButtonEvent(obj);
    }
  }

  void lv_update_task(struct _lv_task_t* task) {
    auto* user_data = static_cast<QuickSettings*>(task->user_data);
    user_data->UpdateScreen();
  }

  void Paint(lv_obj_t* button, lv_obj_t* label, const char* icon, lv_color_t background, lv_color_t iconColor) {
    lv_label_set_text_static(label, icon);
    lv_obj_set_style_local_bg_color(button, LV_BTN_PART_MAIN, LV_STATE_DEFAULT, background);
    lv_obj_set_style_local_text_color(label, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, iconColor);
  }
}

QuickSettings::QuickSettings(Pinetime::Applications::DisplayApp* app,
                             const Pinetime::Controllers::Battery& batteryController,
                             Controllers::DateTime& dateTimeController,
                             Controllers::BrightnessController& brightness,
                             Controllers::MotorController& motorController,
                             Pinetime::Controllers::Settings& settingsController,
                             const Controllers::Ble& bleController,
                             const Controllers::AlarmController& alarmController,
                             Controllers::MusicService& musicService)
  : app {app},
    dateTimeController {dateTimeController},
    brightness {brightness},
    motorController {motorController},
    settingsController {settingsController},
    bleController {bleController},
    musicService {musicService},
    statusIcons(batteryController, bleController, alarmController) {
  statusIcons.Create();

  // This is the distance (padding) between all objects on this screen.
  static constexpr uint8_t innerDistance = 10;

  // Time
  label_time = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_align(label_time, LV_LABEL_ALIGN_CENTER);
  lv_obj_align(label_time, lv_scr_act(), LV_ALIGN_IN_TOP_LEFT, 0, 0);

  static constexpr uint8_t barHeight = 20 + innerDistance;
  static constexpr uint8_t buttonHeight = (LV_VER_RES_MAX - barHeight - innerDistance) / 2;
  static constexpr uint8_t buttonWidth = (LV_HOR_RES_MAX - innerDistance) / 2; // wide buttons
  // static constexpr uint8_t buttonWidth = buttonHeight; // square buttons
  static constexpr uint8_t buttonXOffset = (LV_HOR_RES_MAX - buttonWidth * 2 - innerDistance) / 2;

  lv_style_init(&btn_style);
  lv_style_set_radius(&btn_style, LV_STATE_DEFAULT, buttonHeight / 4);
  lv_style_set_bg_color(&btn_style, LV_STATE_DEFAULT, Colors::bgAlt);

  btn1 = lv_btn_create(lv_scr_act(), nullptr);
  btn1->user_data = this;
  lv_obj_set_event_cb(btn1, ButtonEventHandler);
  lv_obj_add_style(btn1, LV_BTN_PART_MAIN, &btn_style);
  lv_obj_set_size(btn1, buttonWidth, buttonHeight);
  lv_obj_align(btn1, nullptr, LV_ALIGN_IN_TOP_LEFT, buttonXOffset, barHeight);

  btn1_lvl = lv_label_create(btn1, nullptr);
  lv_obj_set_style_local_text_font(btn1_lvl, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &lv_font_sys_48);
  lv_label_set_text_static(btn1_lvl, brightness.GetIcon());

  // Find my phone. (The step count that used to be here has its own screen, one swipe to the right.)
  btn2 = lv_btn_create(lv_scr_act(), nullptr);
  btn2->user_data = this;
  lv_obj_set_event_cb(btn2, ButtonEventHandler);
  lv_obj_add_style(btn2, LV_BTN_PART_MAIN, &btn_style);
  lv_obj_set_size(btn2, buttonWidth, buttonHeight);
  lv_obj_align(btn2, nullptr, LV_ALIGN_IN_TOP_RIGHT, -buttonXOffset, barHeight);

  btn2_lvl = lv_label_create(btn2, nullptr);
  lv_obj_set_style_local_text_font(btn2_lvl, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &lv_font_sys_48);
  lv_label_set_text_static(btn2_lvl, Symbols::phoneRing);

  // The phone's sound mode. (The watch's own alerts are switched in the list of settings.)
  btn3 = lv_btn_create(lv_scr_act(), nullptr);
  btn3->user_data = this;
  lv_obj_set_event_cb(btn3, ButtonEventHandler);
  lv_obj_add_style(btn3, LV_BTN_PART_MAIN, &btn_style);
  lv_obj_set_size(btn3, buttonWidth, buttonHeight);
  lv_obj_align(btn3, nullptr, LV_ALIGN_IN_BOTTOM_LEFT, buttonXOffset, 0);

  btn3_lvl = lv_label_create(btn3, nullptr);
  lv_obj_set_style_local_text_font(btn3_lvl, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &lv_font_sys_48);
  lv_label_set_text_static(btn3_lvl, Symbols::notificationsOn);

  btn4 = lv_btn_create(lv_scr_act(), nullptr);
  btn4->user_data = this;
  lv_obj_set_event_cb(btn4, ButtonEventHandler);
  lv_obj_add_style(btn4, LV_BTN_PART_MAIN, &btn_style);
  lv_obj_set_size(btn4, buttonWidth, buttonHeight);
  lv_obj_align(btn4, nullptr, LV_ALIGN_IN_BOTTOM_RIGHT, -buttonXOffset, 0);

  lv_obj_t* lbl_btn = lv_label_create(btn4, nullptr);
  lv_obj_set_style_local_text_font(lbl_btn, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, &lv_font_sys_48);
  lv_label_set_text_static(lbl_btn, Symbols::settings);

  taskUpdate = lv_task_create(lv_update_task, refreshPeriodMs, LV_TASK_PRIO_MID, this);

  UpdateScreen();
}

QuickSettings::~QuickSettings() {
  lv_style_reset(&btn_style);
  lv_task_del(taskUpdate);
  lv_obj_clean(lv_scr_act());
  settingsController.SaveSettings();
}

void QuickSettings::UpdateScreen() {
  if (refreshesToClock == 0) {
    lv_label_set_text(label_time, dateTimeController.FormattedTime().c_str());
    statusIcons.Update();
    refreshesToClock = clockRefreshes;
  }
  refreshesToClock--;
  ShowPhoneButtons();
}

// Draws the two buttons that belong to the phone, if what they should show has changed
void QuickSettings::ShowPhoneButtons() {
  const TickType_t now = xTaskGetTickCount();
  const bool connected = bleController.IsConnected();

  // The sound mode the phone reports. Anything but the three known modes counts as "not known":
  // no phone, or a phone app from before it reported this.
  uint8_t sound = connected ? musicService.getPhoneSoundMode() : Music::StateUnknown;
  if (sound > Music::SoundOn) {
    sound = Music::StateUnknown;
  }
  if (soundPending) {
    if (!connected || sound == soundWanted || (now - soundTick) >= soundPatience) {
      // The phone is where it was asked to go, or it did not go there: show what it reports
      soundPending = false;
    } else {
      sound = soundWanted;
    }
  }
  if (sound != shownSound) {
    shownSound = sound;
    switch (sound) {
      case Music::SoundOn:
        Paint(btn3, btn3_lvl, Symbols::notificationsOn, Colors::highlight, LV_COLOR_WHITE);
        break;
      case Music::SoundVibrate:
        Paint(btn3, btn3_lvl, Symbols::vibration, vibrateColor, LV_COLOR_WHITE);
        break;
      case Music::SoundMute:
        Paint(btn3, btn3_lvl, Symbols::notificationsOff, muteColor, LV_COLOR_WHITE);
        break;
      default:
        Paint(btn3, btn3_lvl, Symbols::notificationsOn, Colors::bgAlt, offColor);
        break;
    }
  }

  bool ringing = connected && musicService.isPhoneRinging();
  if (findPending) {
    if (!connected || ringing == findWanted || (now - findTick) >= findPatience) {
      findPending = false;
    } else {
      ringing = findWanted;
    }
  }
  const uint8_t find = !connected ? findNoPhone : ringing ? findRinging : findIdle;
  if (find != shownFind) {
    shownFind = find;
    switch (find) {
      case findRinging:
        Paint(btn2, btn2_lvl, Symbols::phoneRing, ringingColor, LV_COLOR_BLACK);
        break;
      case findIdle:
        Paint(btn2, btn2_lvl, Symbols::phoneRing, Colors::bgAlt, LV_COLOR_WHITE);
        break;
      default:
        Paint(btn2, btn2_lvl, Symbols::phoneRing, Colors::bgAlt, offColor);
        break;
    }
  }
}

void QuickSettings::OnButtonEvent(lv_obj_t* object) {
  if (object == btn1) {
    brightness.Step();
    lv_label_set_text_static(btn1_lvl, brightness.GetIcon());
    settingsController.SetBrightness(brightness.Level());

  } else if (object == btn2) {
    if (!bleController.IsConnected()) {
      return;
    }
    // Ring, or stop ringing: the opposite of what the button shows right now
    const bool ringing = findPending ? findWanted : musicService.isPhoneRinging();
    findWanted = !ringing;
    findPending = true;
    findTick = xTaskGetTickCount();
    musicService.FindPhone(findWanted);
    motorController.RunForDuration(tapBuzzMs);
    ShowPhoneButtons();

  } else if (object == btn3) {
    if (!bleController.IsConnected()) {
      return;
    }
    const uint8_t current = soundPending ? soundWanted : musicService.getPhoneSoundMode();
    if (current > Music::SoundOn) {
      // The phone has not said which mode it is in, so there is no next one to ask for
      return;
    }
    // Sound, vibrate, mute, and round again
    soundWanted = current == Music::SoundOn ? Music::SoundVibrate : current == Music::SoundVibrate ? Music::SoundMute : Music::SoundOn;
    soundPending = true;
    soundTick = xTaskGetTickCount();
    musicService.SetPhoneSoundMode(soundWanted);
    motorController.RunForDuration(tapBuzzMs);
    ShowPhoneButtons();

  } else if (object == btn4) {
    settingsController.SetSettingsMenu(0);
    app->StartApp(Apps::Settings, DisplayApp::FullRefreshDirections::Up);
  }
}
