#pragma once

#include <cstdint>
#include "displayapp/screens/Screen.h"
#include <lvgl/lvgl.h>
#include <FreeRTOS.h>
#include "components/datetime/DateTimeController.h"
#include "components/brightness/BrightnessController.h"
#include "components/motor/MotorController.h"
#include "components/settings/Settings.h"
#include "components/battery/BatteryController.h"
#include "displayapp/widgets/StatusIcons.h"

namespace Pinetime {
  namespace Controllers {
    class MusicService;
  }

  namespace Applications {
    namespace Screens {

      /**
       * The settings screen, above the notifications. Four big buttons:
       *
       *   brightness               find my phone
       *   the phone's sound mode   the list of settings
       *
       * - Find my phone makes the phone ring at full volume, also when it is on mute. The button
       *   is orange while the phone rings. A second tap stops it, and it stops by itself after a minute.
       * - The sound button steps the PHONE through sound, vibrate and mute. It always shows the
       *   mode the phone itself reports, so it also follows a change made on the phone.
       *   (The watch's own alerts are switched in the list of settings, under Notifications.)
       * - Both are grey while the phone is not connected.
       *
       * The weather is next to this screen on the left, the steps on the right.
       */
      class QuickSettings : public Screen {
      public:
        QuickSettings(DisplayApp* app,
                      const Pinetime::Controllers::Battery& batteryController,
                      Controllers::DateTime& dateTimeController,
                      Controllers::BrightnessController& brightness,
                      Controllers::MotorController& motorController,
                      Pinetime::Controllers::Settings& settingsController,
                      const Controllers::Ble& bleController,
                      const Controllers::AlarmController& alarmController,
                      Controllers::MusicService& musicService);

        ~QuickSettings() override;

        void OnButtonEvent(lv_obj_t* object);

        void UpdateScreen();

      private:
        void ShowPhoneButtons();

        DisplayApp* app;
        Controllers::DateTime& dateTimeController;
        Controllers::BrightnessController& brightness;
        Controllers::MotorController& motorController;
        Controllers::Settings& settingsController;
        const Controllers::Ble& bleController;
        Controllers::MusicService& musicService;

        lv_task_t* taskUpdate;
        lv_obj_t* label_time;

        lv_style_t btn_style;

        lv_obj_t* btn1;
        lv_obj_t* btn1_lvl;
        lv_obj_t* btn2;
        lv_obj_t* btn2_lvl;
        lv_obj_t* btn3;
        lv_obj_t* btn3_lvl;
        lv_obj_t* btn4;

        // What the phone was just asked for. The button shows it right away, until the phone
        // reports where it really is or has had long enough to do so.
        bool soundPending = false;
        uint8_t soundWanted = 0;
        TickType_t soundTick = 0;
        bool findPending = false;
        bool findWanted = false;
        TickType_t findTick = 0;

        // What the two phone buttons show at the moment (a value neither can have: not drawn yet)
        uint8_t shownSound = 0xfe;
        uint8_t shownFind = 0xfe;

        // Refreshes left until the time and the icons next to it are looked at again
        uint8_t refreshesToClock = 0;

        Widgets::StatusIcons statusIcons;
      };
    }
  }
}
