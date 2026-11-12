#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace Pinetime {
  namespace Controllers {
    class NotificationManager {
    public:
      enum class Categories {
        Unknown,
        SimpleAlert,
        Email,
        News,
        IncomingCall,
        MissedCall,
        Sms,
        VoiceMail,
        Schedule,
        HighProriotyAlert,
        InstantMessage
      };
      // Title and text of one notification, with a '\0' between them
      static constexpr uint8_t MessageSize {160};
      // Name of the app a notification came from, for example "WhatsApp"
      static constexpr uint8_t LabelSize {12};
      static constexpr uint8_t UnknownHour {0xff};

      struct Notification {
        using Id = uint8_t;
        using Idx = uint8_t;

        std::array<char, MessageSize + 1> message {};
        uint8_t size;
        Categories category = Categories::Unknown;
        Id id = 0;
        bool valid = false;

        // The rest is filled in for notifications that come from the Watch Helper phone app.
        // The phone names each one with a number so that it can take it back later; 0 means
        // the notification did not come from the phone app (a notice from the watch itself).
        uint16_t phoneId = 0;
        std::array<char, LabelSize + 1> label {};
        // Time of day the phone received it
        uint8_t hour = UnknownHour;
        uint8_t minute = 0;

        const char* Message() const;
        const char* Title() const;
      };

      void Push(Notification&& notif);
      // Adds a notification from the phone, replacing an earlier one with the same phone number.
      // "alert" marks it as new, which lights the marker on the main screen.
      void Upsert(Notification&& notif, bool alert);
      // The phone took a notification back, or all of them
      void RemoveByPhoneId(uint16_t phoneId);
      void Clear();
      // Notices from the watch itself have nobody to take them back. They go once they were read.
      void RemoveLocal();
      // Notification at a position, 0 being the newest. Not valid if there is none there.
      Notification GetAt(Notification::Idx idx) const;
      // Goes up whenever the list changes, so a screen can tell that it has to look again
      uint32_t Revision() const {
        return revision;
      }

      // The phone's call, as reported by the Watch Helper phone app
      enum class CallStates : uint8_t { Idle = 0, Ringing = 1, Active = 2 };
      static constexpr uint8_t CallerSize {40};
      void SetCall(CallStates state, const char* callerName, size_t callerLength);
      // The caller's name can arrive in two pieces
      void AppendCaller(const char* more, size_t moreLength);
      CallStates CallState() const {
        return callState;
      }
      // Copies the caller's name; "out" has to hold CallerSize + 1 bytes
      void CallerName(char* out) const;
      Notification GetLastNotification() const;
      Notification Get(Notification::Id id) const;
      Notification GetNext(Notification::Id id) const;
      Notification GetPrevious(Notification::Id id) const;
      // Return the index of the notification with the specified id, if not found return NbNotifications()
      Notification::Idx IndexOf(Notification::Id id) const;
      bool ClearNewNotificationFlag();
      bool AreNewNotificationsAvailable() const;
      void Dismiss(Notification::Id id);

      static constexpr size_t MaximumMessageSize() {
        return MessageSize;
      };

      bool IsEmpty() const {
        return size == 0;
      }

      size_t NbNotifications() const;

    private:
      Notification::Id nextId {0};
      Notification::Id GetNextId();
      const Notification& At(Notification::Idx idx) const;
      Notification& At(Notification::Idx idx);
      void DismissIdx(Notification::Idx idx);

      static constexpr uint8_t TotalNbNotifications = 8;
      std::array<Notification, TotalNbNotifications> notifications;
      size_t beginIdx = TotalNbNotifications - 1; // index of the newest notification
      size_t size = 0;                            // number of valid notifications in buffer

      std::atomic<bool> newNotification {false};
      std::atomic<uint32_t> revision {0};

      std::atomic<CallStates> callState {CallStates::Idle};
      std::array<char, CallerSize + 1> caller {};
    };
  }
}
