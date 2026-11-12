package io.github.bahadirkiziltepe.watchhelper;

import android.content.Context;
import android.os.Build;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Builds the text the user copies or shares so the app's behaviour can be checked from afar. */
final class Report {
  private Report() {
  }

  static String build(Core core) {
    StringBuilder sb = new StringBuilder();
    sb.append("WATCH HELPER REPORT\n");
    sb.append("Helper ").append(core.version()).append(", ")
        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)))
        .append('\n');
    sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ")
        .append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
    sb.append("Spotify app: ").append(spotifyVersion(core.app)).append('\n');

    sb.append("\n== Setup ==\n");
    sb.append("Notification access: ").append(core.hasNotificationAccess() ? "yes" : "no").append('\n');
    sb.append("Allowed to keep running: ").append(core.mayRunInBackground() ? "yes" : "no").append('\n');
    sb.append("Gadgetbridge installed: ").append(core.gadgetbridgeInstalled() ? "yes" : "no").append('\n');

    sb.append("\n== Watch ==\n");
    sb.append(core.watch.dump());

    sb.append("\n== Watch updates ==\n");
    sb.append(core.updater.dump());

    sb.append("\n== Notifications ==\n");
    sb.append(core.notifications.dump());
    sb.append("Claude notifications waiting (shown as a dot on the watch): ").append(core.claudeWaiting())
        .append(", buzzes sent for them: ").append(core.claudeBuzzes()).append(", icon read: ")
        .append(core.claudeIconKnown() ? "yes" : "no").append('\n');

    sb.append("\n== Calls ==\n");
    sb.append(core.calls.dump());

    sb.append("\n== Phone ==\n");
    sb.append("Foreground service (Android 17 needs it for volume, sound mode and ringing): ")
        .append(LinkService.running() ? "running" : "not running")
        .append(LinkService.problem().isEmpty() ? "" : ", " + LinkService.problem()).append('\n');
    sb.append(core.sound.dump()).append('\n');
    sb.append(core.finder.dump()).append('\n');
    sb.append("Call history readable: ").append(core.history.allowed() ? "yes" : "no").append(", calls last shown to the watch: ")
        .append(core.history.shown().size()).append(", people suggested for the watch: ")
        .append(core.suggestions.all().size()).append('\n');
    sb.append("\n== Weather ==\n");
    sb.append(core.weather.dump()).append('\n');
    sb.append("\n== Spotify ==\n");
    sb.append(core.spotify.dump());

    sb.append("\n== Playlists, queue and people ==\n");
    sb.append(core.account.dump()).append('\n');
    sb.append(core.library.dump()).append('\n');
    sb.append(core.lists.dump()).append('\n');

    sb.append("\n== What happened ==\n");
    if (core.log.dropped() > 0) {
      sb.append('(').append(core.log.dropped()).append(" older lines left out)\n");
    }
    for (String line : core.log.all()) {
      sb.append(line).append('\n');
    }
    return scrub(sb.toString());
  }

  private static String spotifyVersion(Context app) {
    try {
      return app.getPackageManager().getPackageInfo(SpotifyWatcher.SPOTIFY, 0).versionName;
    } catch (Exception e) {
      return "not found";
    }
  }

  /** Belt and braces: nothing above prints a Bluetooth address, but make sure none slips through. */
  static String scrub(String text) {
    return text.replaceAll("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b", "(address hidden)");
  }
}
