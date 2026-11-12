#pragma once

#include <FreeRTOS.h>
#include <task.h>
#include <lvgl/lvgl.h>
#include <cstdint>
#include <cstdlib>

namespace Pinetime {
  namespace Applications {
    namespace Widgets {
      /**
       * An invisible sheet over the whole screen that tells taps, double taps and holds apart.
       *
       * Create it after everything that should lie under it. Anything created later (a button, say)
       * lies on top of it and keeps its own touches.
       * Call Update() on every refresh of the screen: it reports what the finger just did.
       *
       * The object must stay where it is once Create() has run, because LVGL keeps a pointer to it.
       */
      class TapPad {
      public:
        enum class Events : uint8_t { None, Tap, DoubleTap, Hold };

        /**
         * @param waitForDoubleTap a tap is only reported once it is clear that no second tap follows.
         *        Without it every tap is reported as soon as the finger lifts, and there are no double taps.
         * @param holdDuration how long the finger has to stay down for a hold; 0 for no holds.
         */
        void Create(bool waitForDoubleTap, TickType_t holdDuration) {
          this->waitForDoubleTap = waitForDoubleTap;
          this->holdDuration = holdDuration;

          pad = lv_obj_create(lv_scr_act(), nullptr);
          lv_obj_set_size(pad, LV_HOR_RES, LV_VER_RES);
          lv_obj_set_pos(pad, 0, 0);
          lv_obj_set_style_local_bg_opa(pad, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, LV_OPA_TRANSP);
          lv_obj_set_style_local_border_width(pad, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, 0);
          pad->user_data = this;
          lv_obj_set_event_cb(pad, EventHandler);
        }

        void SetHoldDuration(TickType_t duration) {
          holdDuration = duration;
        }

        /** Touches that begin within this time from now are ignored */
        void Mute(TickType_t now, TickType_t duration) {
          muted = true;
          muteTick = now;
          muteDuration = duration;
        }

        /** A finger is down, or a tap is still waiting to see whether a second one follows */
        bool Busy() const {
          return fingerDown || tapWaiting;
        }

        /** A finger is down and has not moved away: this press can still become a tap or a hold */
        bool Pressing() const {
          return fingerDown && !pressSpent;
        }

        /** Where the finger came down for the tap or hold that was last reported, or is under way */
        lv_point_t PressPoint() const {
          return pressPoint;
        }

        /** True once after the screen was touched (muted touches do not count) */
        bool TakeTouched() {
          const bool result = touched;
          touched = false;
          return result;
        }

        Events Update(TickType_t now) {
          if (fingerDown && !pressSpent) {
            const lv_point_t point = TouchPoint();
            if (std::abs(point.x - pressPoint.x) > slack || std::abs(point.y - pressPoint.y) > slack) {
              // The finger is wandering: neither a tap nor a hold
              pressSpent = true;
            } else if (holdDuration != 0 && (now - pressTick) >= holdDuration) {
              pressSpent = true;
              tapWaiting = false;
              pending = Events::Hold;
            }
          }

          if (tapWaiting && !fingerDown && (now - tapTick) >= doubleTapWindow) {
            tapWaiting = false;
            pending = Events::Tap;
          }

          const Events result = pending;
          pending = Events::None;
          return result;
        }

      private:
        // A second tap makes a double tap if it starts within this time after the first one ended
        static constexpr TickType_t doubleTapWindow = pdMS_TO_TICKS(300);
        // A finger that moves further than this many pixels is neither tapping nor holding
        static constexpr int slack = 30;

        static void EventHandler(lv_obj_t* obj, lv_event_t event) {
          static_cast<TapPad*>(obj->user_data)->OnEvent(event);
        }

        // Where the finger is. Only meaningful while the screen is being touched.
        static lv_point_t TouchPoint() {
          lv_point_t point = {0, 0};
          lv_indev_t* indev = lv_indev_get_next(nullptr);
          if (indev != nullptr) {
            lv_indev_get_point(indev, &point);
          }
          return point;
        }

        void OnEvent(lv_event_t event) {
          const TickType_t now = xTaskGetTickCount();
          switch (event) {
            case LV_EVENT_PRESSED:
              fingerDown = true;
              pressTick = now;
              pressPoint = TouchPoint();
              if (muted && (now - muteTick) >= muteDuration) {
                muted = false;
              }
              pressSpent = muted;
              if (!muted) {
                touched = true;
              }
              break;
            case LV_EVENT_RELEASED:
              if (!fingerDown) {
                break;
              }
              fingerDown = false;
              if (pressSpent) {
                break;
              }
              if (!waitForDoubleTap) {
                pending = Events::Tap;
              } else if (tapWaiting) {
                tapWaiting = false;
                pending = Events::DoubleTap;
              } else {
                // Update() reports it once it is clear that no second tap follows
                tapWaiting = true;
                tapTick = now;
              }
              break;
            case LV_EVENT_PRESS_LOST:
              // The press turned into a swipe
              fingerDown = false;
              break;
            default:
              break;
          }
        }

        lv_obj_t* pad = nullptr;
        bool waitForDoubleTap = true;
        TickType_t holdDuration = 0;

        // A finger is down, and whether this press can still become a tap or a hold
        bool fingerDown = false;
        bool pressSpent = false;
        TickType_t pressTick = 0;
        lv_point_t pressPoint = {0, 0};
        // One tap has been seen and is waiting to find out whether a second one follows
        bool tapWaiting = false;
        TickType_t tapTick = 0;

        bool muted = false;
        TickType_t muteTick = 0;
        TickType_t muteDuration = 0;

        bool touched = false;
        Events pending = Events::None;
      };
    }
  }
}
