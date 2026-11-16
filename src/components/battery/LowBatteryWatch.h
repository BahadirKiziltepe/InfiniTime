#pragma once

#include <cstdint>

namespace Pinetime {
  namespace Controllers {
    /**
     * Notices the moment a battery has run down to its low mark and to its critical mark, so that
     * the wearer is told once each time. Used for the watch's own battery and the phone's (15 % and
     * 5 %) and for the earbuds' (20 % and 10 %).
     */
    class LowBatteryWatch {
    public:
      enum class Warnings : uint8_t { None, Low, Critical };

      // The watch and the phone
      static constexpr uint8_t Low = 15;
      static constexpr uint8_t Critical = 5;
      // Earbuds hold little, and most of them only count in steps of ten
      static constexpr uint8_t EarbudsLow = 20;
      static constexpr uint8_t EarbudsCritical = 10;

      constexpr LowBatteryWatch() = default;
      constexpr LowBatteryWatch(uint8_t low, uint8_t critical) : low {low}, critical {critical} {
      }

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
          toldLow = percent <= low;
          toldCritical = percent <= critical;
          return Warnings::None;
        }
        // Charged far enough for the next warning to mean something again
        if (percent >= low + Margin) {
          toldLow = false;
        }
        if (percent >= critical + Margin) {
          toldCritical = false;
        }
        Warnings warning = Warnings::None;
        if (percent <= critical && !toldCritical) {
          toldCritical = true;
          toldLow = true;
          warning = Warnings::Critical;
        } else if (percent <= low && !toldLow) {
          toldLow = true;
          warning = Warnings::Low;
        }
        // On the charger the level only goes up: nothing to warn about
        return charging ? Warnings::None : warning;
      }

    private:
      static constexpr uint8_t Margin = 5;

      uint8_t low = Low;
      uint8_t critical = Critical;
      bool known = false;
      bool toldLow = false;
      bool toldCritical = false;
    };
  }
}
