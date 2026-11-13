#pragma once

#include <FreeRTOS.h>
#include <lvgl/src/lv_core/lv_obj.h>
#include <chrono>
#include <cstdint>
#include <string>
#include "displayapp/Controllers.h"
#include "displayapp/apps/Apps.h"
#include "displayapp/screens/Screen.h"
#include "components/datetime/DateTimeController.h"
#include "utility/DirtyValue.h"

namespace Pinetime {
  namespace Controllers {
    class Settings;
    class Battery;
    class Ble;
    class NotificationManager;
    class MotorController;
    class MusicService;
  }

  namespace Applications {
    namespace Screens {

      /**
       * Main screen: a music remote with the clock in the top left corner.
       *
       * - Top bar: time on the left. On the right the battery as plain numbers, the phone's and then the
       *   watch's ("55|95"), and under them the phone's media volume in the phone's own steps.
       * - Below it: track, artist and a progress bar.
       * - No playback buttons. The screen itself is the button:
       *   tap = play or pause, double tap = next track, tap and hold = previous track.
       * - Bottom row: shuffle on the left, repeat on the right, and between them a plus that is only
       *   there while the current song is not saved yet. These three are acted on by the Watch Helper
       *   phone app, which also reports what each one currently shows.
       * - Swiping left or right lowers or raises the phone's media volume.
       * - Swiping down or up is left to DisplayApp (quick settings / notifications).
       */
      class WatchFaceRemote : public Screen {
      public:
        WatchFaceRemote(Controllers::DateTime& dateTimeController,
                        const Controllers::Battery& batteryController,
                        const Controllers::Ble& bleController,
                        Controllers::NotificationManager& notificationManager,
                        Controllers::Settings& settingsController,
                        Controllers::MotorController& motorController,
                        Controllers::MusicService& musicService);
        ~WatchFaceRemote() override;

        void Refresh() override;
        bool OnTouchEvent(TouchEvents event) override;

        void OnButtonEvent(lv_obj_t* obj, lv_event_t event);
        void OnTapAreaEvent(lv_event_t event);

      private:
        void ChangeVolume(bool up);
        void UpdateLevels();
        void UpdateMusic(TickType_t now);
        void UpdateExtraButtons(TickType_t now);
        void UpdateTaps(TickType_t now);
        void NoteScreenOn(TickType_t now);
        bool JustWoke(TickType_t now);
        void TogglePlay(TickType_t now);
        void Skip(bool forward, TickType_t now);
        void SendExtra(char event);
        void ShowPlayerIcon();

        Utility::DirtyValue<std::chrono::time_point<std::chrono::system_clock, std::chrono::minutes>> currentDateTime {};
        Utility::DirtyValue<bool> notificationState {};

        lv_obj_t* labelTime;
        lv_obj_t* labelAmPm;
        lv_obj_t* labelBattery;
        lv_obj_t* notificationIcon;
        lv_obj_t* waitingDot;
        lv_obj_t* waitingIcon;
        lv_img_dsc_t waitingIconImage {};
        lv_obj_t* labelVolume;
        lv_obj_t* labelTrack;
        lv_obj_t* labelArtist;
        lv_obj_t* labelPlayer;
        lv_obj_t* barProgress;
        lv_obj_t* tapArea;
        lv_obj_t* btnShuffle;
        lv_obj_t* btnAdd;
        lv_obj_t* btnRepeat;
        lv_obj_t* labelShuffle;
        lv_obj_t* labelAdd;
        lv_obj_t* labelRepeat;

        lv_style_t extraBtnStyle;

        Controllers::DateTime& dateTimeController;
        const Controllers::Battery& batteryController;
        const Controllers::Ble& bleController;
        Controllers::NotificationManager& notificationManager;
        Controllers::Settings& settingsController;
        Controllers::MotorController& motorController;
        Controllers::MusicService& musicService;

        std::string track;
        bool smartPick = false;
        std::string artist;
        bool trackDrawn = false;
        bool shownConnected = false;
        bool playing = false;
        bool progressVisible = false;

        /** Music info is polled a couple of times per second, not on every display refresh */
        bool musicPolled = false;
        TickType_t lastMusicPoll = 0;

        /** After a tap, show the new play state right away and give the phone a moment to confirm it */
        bool playOverride = false;
        TickType_t playOverrideTick = 0;

        /** After a skip, the player icon shows which way it went for a moment */
        bool skipHintVisible = false;
        bool skipHintForward = false;
        TickType_t skipHintTick = 0;

        /** What the top right corner shows. Starts at a value nothing uses, so the first update draws it. */
        uint8_t shownWatchBattery = 0xfe;
        uint8_t shownPhoneBattery = 0xfe;
        bool shownWatchCharging = false;
        bool shownPhoneCharging = false;
        uint8_t shownVolume = 0xfe;
        bool shownWaiting = false;
        uint32_t shownIconRevision = 0;

        /** Touch pad: a finger is down on it, and whether this press can still become a tap or a hold */
        bool fingerDown = false;
        bool pressSpent = false;
        TickType_t pressTick = 0;
        lv_point_t pressPoint = {0, 0};
        /** One tap has been seen and is waiting to find out whether a second one follows */
        bool tapWaiting = false;
        TickType_t tapTick = 0;
        /** A press that slid off one of the bottom buttons onto the pad is not a tap */
        bool slidOffButton = false;
        TickType_t slidOffTick = 0;

        /** Nothing here runs while the display is off, so a long gap between two calls means the watch just woke up */
        TickType_t lastActiveTick = 0;
        bool woke = false;
        TickType_t wokeTick = 0;

        /** What the bottom row currently shows. Starts at a value no state uses, so the first update draws it. */
        uint8_t shownShuffle = 0xfe;
        uint8_t shownRepeat = 0xfe;
        bool shownAdd = false;
        /** The plus was tapped: it stays away while the phone catches up, and the pad under it ignores a second tap */
        bool addOverride = false;
        TickType_t addOverrideTick = 0;
        bool padMuted = false;
        TickType_t padMuteTick = 0;

        lv_task_t* taskRefresh;
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
