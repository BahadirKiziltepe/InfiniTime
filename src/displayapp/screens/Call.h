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
    class MotorController;
    class MusicService;
  }

  namespace Applications {
    class DisplayApp;

    namespace Screens {

      /**
       * The phone's call, as reported by the Watch Helper phone app.
       *
       * While the phone rings: who is calling. A tap answers, tap and hold declines. The watch buzzes
       * without a break until one of the two happens, the call stops ringing, the screen is left, or a
       * sideways swipe silences it.
       *
       * During the call: a swipe to the left or right turns the volume of the call down or up.
       *
       * For as long as there is a call, this screen sits between the main screen (swipe down) and the
       * notifications (swipe up). It closes by itself when the call is over.
       */
      class Call : public Screen {
      public:
        Call(DisplayApp* app,
             Pinetime::Controllers::NotificationManager& notificationManager,
             Pinetime::Controllers::MotorController& motorController,
             Pinetime::Controllers::MusicService& musicService,
             System::SystemTask& systemTask,
             bool ring);
        ~Call() override;

        void Refresh() override;
        bool OnTouchEvent(Pinetime::Applications::TouchEvents event) override;

      private:
        using CallStates = Pinetime::Controllers::NotificationManager::CallStates;
        enum class Actions : uint8_t { None, Answering, Declining };

        void Draw(CallStates state);
        void StopBuzz();

        DisplayApp* app;
        Pinetime::Controllers::NotificationManager& notificationManager;
        Pinetime::Controllers::MotorController& motorController;
        Pinetime::Controllers::MusicService& musicService;
        System::WakeLock wakeLock;

        lv_obj_t* labelState;
        lv_obj_t* labelCaller;
        lv_obj_t* labelHint;
        lv_obj_t* labelVolume;
        Widgets::TapPad tapPad;

        // What is on the screen, to redraw only when it changes
        bool drawn = false;
        CallStates shownState = CallStates::Idle;
        Actions shownAction = Actions::None;
        uint32_t shownRevision = 0;

        // What the wearer just asked for, until the phone reports that it happened
        Actions action = Actions::None;
        TickType_t actionTick = 0;

        // Buzzing for a ringing call
        bool buzzing = false;
        TickType_t buzzStartTick = 0;
        TickType_t lastBuzzTick = 0;

        bool volumeHintVisible = false;
        TickType_t volumeHintTick = 0;

        lv_task_t* taskRefresh;
      };
    }
  }
}
