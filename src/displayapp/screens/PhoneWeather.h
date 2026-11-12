#pragma once

#include <lvgl/lvgl.h>
#include <FreeRTOS.h>
#include <array>
#include <cstdint>
#include "displayapp/screens/Screen.h"
#include "components/ble/MusicService.h"

namespace Pinetime {
  namespace Controllers {
    class Ble;
    class Settings;
  }

  namespace Applications {
    namespace Screens {

      /**
       * The weather where the phone is. It lies next to the settings screen, on its left.
       *
       *                  Partly cloudy     the weather in words, in the colour of its kind
       *   54°F                   Feels     (big)  what it is, and what it feels like
       *                            50°
       *   Wind 12 mph
       *   Humidity 45%
       *   Rain today 60%                   the chance of rain (or snow) for the whole day
       *    5p  6p  7p  8p  9p              and hour by hour for the next ten hours, in percent
       *    10  20  40  60  60
       *   10p 11p 12a  1a  2a
       *    30  20  10   0   0
       *
       * Colours. The words at the top: yellow for sun, white for a clear night, grey for clouds
       * and fog, blue for rain, light blue for snow, violet for freezing rain, orange for a
       * thunderstorm. A chance of rain: blue. Of snow: light blue, and the day's line then reads
       * "Snow today". A chance under 20 % is grey whatever it would be.
       *
       * The time the numbers are from only shows, in orange at the top left, when they are not
       * what the phone would send right now: it could not get newer ones, or it is not connected.
       * (A phone app that does not send the weather in words gets the time at the top right, as
       * before.)
       *
       * The watch asks the Watch Helper phone app whenever this screen opens. The app looks up where
       * the phone is, asks a weather service and answers; what it sent last is shown meanwhile.
       */
      class PhoneWeather : public Screen {
      public:
        PhoneWeather(Pinetime::Controllers::MusicService& musicService,
                     const Pinetime::Controllers::Ble& bleController,
                     Pinetime::Controllers::Settings& settingsController);
        ~PhoneWeather() override;

        void Refresh() override;

      private:
        using Music = Pinetime::Controllers::MusicService;

        void Ask(TickType_t now);
        void Draw(TickType_t now);

        Pinetime::Controllers::MusicService& musicService;
        const Pinetime::Controllers::Ble& bleController;
        Pinetime::Controllers::Settings& settingsController;

        // "Weather", while there are no numbers to show
        lv_obj_t* labelTitle;
        // The weather in words, top right; and when the numbers are from, if that is worth saying
        lv_obj_t* labelCondition;
        lv_obj_t* labelTime;
        lv_obj_t* labelTemperature;
        lv_obj_t* labelFeels;
        lv_obj_t* labelWind;
        lv_obj_t* labelHumidity;
        lv_obj_t* labelRain;
        // Two rows of five hours: the hours, and under them the chance of rain
        std::array<lv_obj_t*, 2> labelHours;
        std::array<lv_obj_t*, 2> labelChances;
        lv_obj_t* labelNote;

        Music::Weather weather {};
        uint32_t seenRevision = 0;
        bool connected = false;
        // The phone was asked and has not answered yet; and it was given up on
        bool waiting = false;
        bool unanswered = false;
        TickType_t askedTick = 0;
        bool needsDraw = true;

        lv_task_t* taskRefresh;
      };
    }
  }
}
