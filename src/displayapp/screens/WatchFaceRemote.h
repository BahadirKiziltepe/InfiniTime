#pragma once

#include <FreeRTOS.h>
#include <lvgl/src/lv_core/lv_obj.h>
#include <chrono>
#include <cstdint>
#include <string>
#include "displayapp/Controllers.h"
#include "displayapp/apps/Apps.h"
#include "displayapp/screens/Screen.h"
#include "displayapp/widgets/StatusIcons.h"
#include "components/datetime/DateTimeController.h"
#include "utility/DirtyValue.h"

namespace Pinetime {
  namespace Controllers {
    class Settings;
    class Battery;
    class Ble;
    class AlarmController;
    class NotificationManager;
    class MotorController;
    class MusicService;
  }

  namespace Applications {
    namespace Screens {

      /**
       * Main screen: a music remote with the clock in the top left corner.
       *
       * - Top bar: time on the left, bluetooth and battery on the right.
       * - Below it: track, artist and a progress bar.
       * - First row of buttons: previous / play-pause / next.
       * - Second row: shuffle / repeat / add. These need a phone-side helper that does not exist yet,
       *   so for now they are drawn dimmed and do not react.
       * - Swiping left or right lowers or raises the phone's media volume.
       * - Swiping up or down is left to DisplayApp (quick settings / notifications).
       */
      class WatchFaceRemote : public Screen {
      public:
        WatchFaceRemote(Controllers::DateTime& dateTimeController,
                        const Controllers::Battery& batteryController,
                        const Controllers::Ble& bleController,
                        const Controllers::AlarmController& alarmController,
                        Controllers::NotificationManager& notificationManager,
                        Controllers::Settings& settingsController,
                        Controllers::MotorController& motorController,
                        Controllers::MusicService& musicService);
        ~WatchFaceRemote() override;

        void Refresh() override;
        bool OnTouchEvent(TouchEvents event) override;

        void OnButtonEvent(lv_obj_t* obj, lv_event_t event);

      private:
        void ChangeVolume(bool up);
        void UpdateMusic(TickType_t now);
        void SetPlaying(bool isPlaying);

        Utility::DirtyValue<std::chrono::time_point<std::chrono::system_clock, std::chrono::minutes>> currentDateTime {};
        Utility::DirtyValue<bool> notificationState {};

        lv_obj_t* labelTime;
        lv_obj_t* labelAmPm;
        lv_obj_t* notificationIcon;
        lv_obj_t* labelVolume;
        lv_obj_t* labelTrack;
        lv_obj_t* labelArtist;
        lv_obj_t* barProgress;
        lv_obj_t* btnPrev;
        lv_obj_t* btnPlayPause;
        lv_obj_t* btnNext;
        lv_obj_t* labelPlayPause;
        lv_obj_t* btnShuffle;
        lv_obj_t* btnRepeat;
        lv_obj_t* btnAdd;

        lv_style_t btnStyle;
        lv_style_t extraBtnStyle;

        Controllers::DateTime& dateTimeController;
        const Controllers::Ble& bleController;
        Controllers::NotificationManager& notificationManager;
        Controllers::Settings& settingsController;
        Controllers::MotorController& motorController;
        Controllers::MusicService& musicService;

        std::string track;
        std::string artist;
        bool playing = false;
        bool progressVisible = false;

        /** Music info is polled a couple of times per second, not on every display refresh */
        bool musicPolled = false;
        TickType_t lastMusicPoll = 0;

        /** After a tap on play/pause, show the new state right away and give the phone a moment to confirm it */
        bool playOverride = false;
        TickType_t playOverrideTick = 0;

        bool volumeHintVisible = false;
        TickType_t volumeHintTick = 0;

        lv_task_t* taskRefresh;
        Widgets::StatusIcons statusIcons;
      };
    }

    template <>
    struct WatchFaceTraits<WatchFace::Remote> {
      static constexpr WatchFace watchFace = WatchFace::Remote;
      static constexpr const char* name = "Remote";

      static Screens::Screen* Create(AppControllers& controllers) {
        return new Screens::WatchFaceRemote(controllers.dateTimeController,
                                            controllers.batteryController,
                                            controllers.bleController,
                                            controllers.alarmController,
                                            controllers.notificationManager,
                                            controllers.settingsController,
                                            controllers.motorController,
                                            *controllers.musicService);
      };

      static bool IsAvailable(Pinetime::Controllers::FS& /*filesystem*/) {
        return true;
      }
    };
  }
}
