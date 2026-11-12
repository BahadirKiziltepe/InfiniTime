#pragma once

#include <cstdint>

namespace Pinetime {
  namespace Controllers {
    /**
     * Notices the moment a battery has run down to 15 % and to 5 %, so that the wearer is told once
     * each time. Used for the watch's own battery and for the phone's.
     */
    class LowBatteryWatch {
    public:
      enum class Warnings : uint8_t { None, Low, Critical };

      static constexpr uint8_t Low = 15;
      static constexpr uint8_t Critical = 5;

      // Takes the newest level in percent and says whether the wearer should be told about it now.
      // A level above 100 means "not known" and changes nothing.
      Warnings Update(uint8_t percent, bool charging) {
        if (percent > 100) {
          return Warnings::None;
        }
        if (!known) {
          // There is nothing to compare the first level with. A battery that is already low when
          // the watch starts, or when the phone is first heard from, is not news.
          known = true;
          toldLow = percent <= Low;
          toldCritical = percent <= Critical;
          return Warnings::None;
        }
        // Charged far enough for the next warning to mean something again
        if (percent >= Low + Margin) {
          toldLow = false;
        }
        if (percent >= Critical + Margin) {
          toldCritical = false;
        }
        Warnings warning = Warnings::None;
        if (percent <= Critical && !toldCritical) {
          toldCritical = true;
          toldLow = true;
          warning = Warnings::Critical;
        } else if (percent <= Low && !toldLow) {
          toldLow = true;
          warning = Warnings::Low;
        }
        // On the charger the level only goes up: nothing to warn about
        return charging ? Warnings::None : warning;
      }

    private:
      static constexpr uint8_t Margin = 5;

      bool known = false;
      bool toldLow = false;
      bool toldCritical = false;
    };
  }
}
