#pragma once

#include <lvgl/lvgl.h>
#include <FreeRTOS.h>
#include <cstdint>
#include "displayapp/screens/Screen.h"
#include "displayapp/widgets/TapPad.h"
#include "components/ble/NotificationManager.h"
#include "systemtask/SystemTask.h"
#include "systemtask/WakeLock.h"

namespace Pinetime {
  namespace Controllers {
    class DateTime;
    class MotorController;
    class MusicService;
    class Settings;
  }

  namespace Applications {
    class DisplayApp;

    namespace Screens {

      /**
       * The phone's call, as reported by the Watch Helper phone app.
       *
       * While the phone rings: who is calling. A tap answers, a swipe in any direction declines.
       * The watch buzzes without a break until one of the two happens, the call stops ringing or
       * the screen is left.
       *
       * During the call: how long it has been going on, and in the top right corner the volume of
       * the call as a number, in the phone's own steps like the one on the main screen. A swipe to
       * the left or right turns that volume down or up, and a tap hangs up.
       *
       * The time of day is in the top left corner all along, where the main screen has it.
       *
       * Nothing is held on this screen, and no swipe leads away from it: for as long as there is
       * a call, this is the screen. (The side button still does what it does.) It closes by itself
       * when the call is over.
       *
       * A tap that hangs up must be meant. So a touch does not count if it began before the screen
       * was ready for it: while the screen was coming up, in the first moments of the call (the tap
       * that answered it may have a second one behind it), or as the watch was being woken.
       */
      class Call : public Screen {
      public:
        Call(DisplayApp* app,
             Pinetime::Controllers::NotificationManager& notificationManager,
             Pinetime::Controllers::MotorController& motorController,
             Pinetime::Controllers::MusicService& musicService,
             Pinetime::Controllers::DateTime& dateTimeController,
             Pinetime::Controllers::Settings& settingsController,
             System::SystemTask& systemTask,
             bool ring);
        ~Call() override;

        void Refresh() override;
        bool OnTouchEvent(Pinetime::Applications::TouchEvents event) override;

      private:
        using CallStates = Pinetime::Controllers::NotificationManager::CallStates;
        enum class Actions : uint8_t { None, Answering, Declining, Ending };

        void Draw(CallStates state);
        void Decline(TickType_t now);
        void DrawClock();
        void DrawDuration(CallStates state);
        void DrawVolume(CallStates state, TickType_t now);
        void StopBuzz();

        DisplayApp* app;
        Pinetime::Controllers::NotificationManager& notificationManager;
        Pinetime::Controllers::MotorController& motorController;
        Pinetime::Controllers::MusicService& musicService;
        Pinetime::Controllers::DateTime& dateTimeController;
        Pinetime::Controllers::Settings& settingsController;
        System::WakeLock wakeLock;

        lv_obj_t* labelTime;
        lv_obj_t* labelAmPm;
        lv_obj_t* labelState;
        lv_obj_t* labelCaller;
        lv_obj_t* labelDuration;
        lv_obj_t* labelHint;
        lv_obj_t* labelVolume;
        Widgets::TapPad tapPad;

        // When the screen came up, and when it was last refreshed (it is not while the watch sleeps)
        TickType_t appearTick = 0;
        TickType_t refreshTick = 0;
        // What the call was at the last refresh, to notice the moment it is answered
        CallStates lastState = CallStates::Idle;

        // What is on the screen, to redraw only when it changes
        bool drawn = false;
        CallStates shownState = CallStates::Idle;
        Actions shownAction = Actions::None;
        uint32_t shownRevision = 0;
        // Starting at values nothing uses, so the first refresh draws them
        uint8_t shownHour = 0xff;
        uint8_t shownMinute = 0xff;
        uint32_t shownSeconds = 0xffffffff;
        uint8_t shownVolume = 0xfe;

        // What the wearer just asked for, until the phone reports that it happened
        Actions action = Actions::None;
        TickType_t actionTick = 0;

        // Buzzing for a ringing call
        bool buzzing = false;
        TickType_t buzzStartTick = 0;
        TickType_t lastBuzzTick = 0;

        // An arrow in place of the number, for a phone app that does not say the number
        bool volumeHintVisible = false;
        TickType_t volumeHintTick = 0;

        lv_task_t* taskRefresh;
      };
    }
  }
}
