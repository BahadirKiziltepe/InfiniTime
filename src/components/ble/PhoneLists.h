#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace Pinetime {
  namespace Controllers {
    /**
     * Lists that live on the phone and are shown on the watch one page at a time: the play queue,
     * the playlists, the songs of one playlist and the people the wearer chose to call from the watch.
     *
     * The watch asks the Watch Helper phone app for a page (see MusicService::RequestList). The answer
     * arrives in small pieces on the Bluetooth task and is put together here; a screen on the display
     * task picks up the finished page. Only one page is kept: there is no room on the watch for more,
     * and the phone has all of it anyway.
     */
    class PhoneLists {
    public:
      enum class Kinds : uint8_t { None = 0, Queue = 1, Playlists = 2, Songs = 3, Contacts = 4 };

      static constexpr uint8_t RowsPerPage = 4;
      // A row's text: two lines on the screen
      static constexpr uint8_t RowSize = 33;
      static constexpr uint8_t TitleSize = 17;
      // A sentence shown in place of the rows, for example why a list is empty
      static constexpr uint8_t NoteSize = 72;
      static constexpr uint8_t ResultSize = 17;

      // A song Spotify picked itself: it is not in the playlist that is playing
      static constexpr uint8_t RowSmart = 0x01;
      // The song that is playing right now
      static constexpr uint8_t RowPlaying = 0x02;
      // A playlist whose songs Spotify does not hand out (one the wearer only follows)
      static constexpr uint8_t RowClosed = 0x04;

      // What the phone says about something the watch asked it to do
      static constexpr uint8_t ResultDone = 0;
      static constexpr uint8_t ResultFailed = 1;
      // The list on the phone is no longer the one this page came from: ask for the page again
      static constexpr uint8_t ResultStale = 2;

      struct Row {
        std::array<char, RowSize + 1> text {};
        uint8_t flags = 0;
      };

      struct Page {
        Kinds kind = Kinds::None;
        // Number of the request this page answers
        uint8_t token = 0;
        // Names the list on the phone that the page was cut from
        uint8_t snapshot = 0;
        uint8_t count = 0;
        uint16_t offset = 0;
        uint16_t total = 0;
        std::array<Row, RowsPerPage> rows {};
        std::array<char, TitleSize + 1> title {};
        std::array<char, NoteSize + 1> note {};
      };

      struct Result {
        Kinds kind = Kinds::None;
        uint8_t code = ResultDone;
        std::array<char, ResultSize + 1> text {};
      };

      // ---- Bluetooth task: the pieces of a page as they arrive ----

      void Begin(Kinds kind, uint8_t token, uint16_t offset, uint16_t total, uint8_t count, uint8_t snapshot) {
        incoming = {};
        incoming.kind = kind;
        incoming.token = token;
        incoming.offset = offset;
        incoming.total = total;
        incoming.count = count <= RowsPerPage ? count : RowsPerPage;
        incoming.snapshot = snapshot;
        incomingActive = true;
      }

      void RowText(uint8_t token, uint8_t slot, bool first, uint8_t flags, const char* text, size_t length) {
        if (!Accepts(token) || slot >= RowsPerPage) {
          return;
        }
        Row& row = incoming.rows[slot];
        if (first) {
          row.flags = flags;
          row.text[0] = '\0';
        }
        Append(row.text.data(), RowSize, text, length);
      }

      void Title(uint8_t token, const char* text, size_t length) {
        if (Accepts(token)) {
          Append(incoming.title.data(), TitleSize, text, length);
        }
      }

      void Note(uint8_t token, const char* text, size_t length) {
        if (Accepts(token)) {
          Append(incoming.note.data(), NoteSize, text, length);
        }
      }

      void End(uint8_t token) {
        if (!Accepts(token)) {
          return;
        }
        incomingActive = false;
        // An odd number tells a reader that the page is being replaced right now
        sequence++;
        shown = incoming;
        sequence++;
        revision++;
      }

      void SetResult(Kinds kind, uint8_t code, const char* text, size_t length) {
        sequence++;
        result = {};
        result.kind = kind;
        result.code = code;
        Append(result.text.data(), ResultSize, text, length);
        resultWaiting = true;
        sequence++;
        revision++;
      }

      // The phone is gone: nothing here is worth keeping
      void Reset() {
        incomingActive = false;
        sequence++;
        shown = {};
        result = {};
        resultWaiting = false;
        sequence++;
        revision++;
        playlistsOffset = 0;
        parent = 0;
        parentName[0] = '\0';
      }

      // ---- Display task ----

      // Goes up whenever a page or a result has arrived
      uint32_t Revision() const {
        return revision;
      }

      // Copies the newest page. False if it was being replaced at that moment: look again on the next refresh.
      bool Read(Page& out) const {
        for (int attempt = 0; attempt < 3; attempt++) {
          const uint32_t before = sequence;
          if ((before & 1u) != 0) {
            continue;
          }
          out = shown;
          if (sequence == before) {
            return true;
          }
        }
        return false;
      }

      // Hands out what the phone said about the last thing it was asked to do, once
      bool TakeResult(Result& out) {
        if (!resultWaiting) {
          return false;
        }
        const uint32_t before = sequence;
        if ((before & 1u) != 0) {
          return false;
        }
        out = result;
        if (sequence != before) {
          return false;
        }
        resultWaiting = false;
        return true;
      }

      // Every request gets its own number, so an answer that comes late is not taken for the current one
      uint8_t NextToken() {
        lastToken = static_cast<uint8_t>(lastToken == 0xff ? 1 : lastToken + 1);
        return lastToken;
      }

      // The playlist whose songs are shown, and where the list of playlists was when it was opened,
      // so that coming back from the songs lands on the same page
      void OpenPlaylist(uint16_t index, const char* name) {
        parent = index;
        parentName[0] = '\0';
        Append(parentName.data(), TitleSize, name, std::strlen(name));
      }

      uint16_t Parent() const {
        return parent;
      }

      const char* ParentName() const {
        return parentName.data();
      }

      void RememberPlaylistsOffset(uint16_t offset) {
        playlistsOffset = offset;
      }

      uint16_t PlaylistsOffset() const {
        return playlistsOffset;
      }

    private:
      bool Accepts(uint8_t token) const {
        return incomingActive && incoming.token == token;
      }

      // Adds text to what is already in "target", which holds "capacity" characters and the closing 0
      static void Append(char* target, size_t capacity, const char* text, size_t length) {
        const size_t used = std::strlen(target);
        if (text == nullptr || used >= capacity) {
          return;
        }
        const size_t count = length < capacity - used ? length : capacity - used;
        std::memcpy(target + used, text, count);
        target[used + count] = '\0';
      }

      Page incoming {};
      bool incomingActive = false;

      Page shown {};
      Result result {};
      std::atomic<bool> resultWaiting {false};
      std::atomic<uint32_t> sequence {0};
      std::atomic<uint32_t> revision {0};

      uint8_t lastToken = 0;
      uint16_t parent = 0;
      std::array<char, TitleSize + 1> parentName {};
      uint16_t playlistsOffset = 0;
    };
  }
}
