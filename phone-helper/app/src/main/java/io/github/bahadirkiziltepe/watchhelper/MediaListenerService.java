package io.github.bahadirkiziltepe.watchhelper;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Holding notification access is what lets the app see Spotify's player, and it is also what makes
 * Android keep the app running in the background and start it again after a restart.
 *
 * <p>Since build 5 it is used for what its name says as well: notifications from the messaging
 * apps listed in NotificationRelay are passed on to the watch, and call notifications tell the
 * watch that the phone is ringing. Everything else that arrives here is ignored.
 */
public class MediaListenerService extends NotificationListenerService {
  /** The running service, so the app can ask Android which notifications are showing right now. */
  private static MediaListenerService active;

  @Override
  public void onListenerConnected() {
    active = this;
    Core.get(this).onListenerConnected();
  }

  @Override
  public void onListenerDisconnected() {
    active = null;
    Core.get(this).onListenerDisconnected();
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
    if (sbn != null) {
      Core.get(this).onNotificationPosted(sbn, rankingMap);
    }
  }

  @Override
  public void onNotificationRemoved(StatusBarNotification sbn) {
    if (sbn != null) {
      Core.get(this).onNotificationRemoved(sbn);
    }
  }

  /** The notifications on the phone right now, or null while notification access is not up. */
  static StatusBarNotification[] showing() {
    MediaListenerService service = active;
    if (service == null) {
      return null;
    }
    try {
      return service.getActiveNotifications();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Clears a notification on the phone, as swiping it away would. Returns false if Android could
   * not be asked. Whether it really went is seen when Android reports it removed.
   */
  static boolean dismiss(String key) {
    MediaListenerService service = active;
    if (service == null || key == null) {
      return false;
    }
    try {
      StatusBarNotification[] all = service.getActiveNotifications();
      StatusBarNotification target = null;
      for (int i = 0; all != null && i < all.length; i++) {
        if (all[i] != null && key.equals(all[i].getKey())) {
          target = all[i];
        }
      }
      service.cancelNotification(key);
      if (target == null) {
        return true;
      }
      // An app's notifications hang under a summary. When the last of them goes, the summary would
      // stay behind as an empty shell, so it goes too.
      String group = target.getGroupKey();
      boolean othersLeft = false;
      java.util.List<String> summaries = new java.util.ArrayList<>();
      for (StatusBarNotification other : all) {
        if (other == null || other == target || group == null || !group.equals(other.getGroupKey())) {
          continue;
        }
        if (other.getNotification() != null
            && (other.getNotification().flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) {
          summaries.add(other.getKey());
        } else {
          othersLeft = true;
        }
      }
      if (!othersLeft) {
        for (String summary : summaries) {
          service.cancelNotification(summary);
        }
      }
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  static RankingMap ranking() {
    MediaListenerService service = active;
    if (service == null) {
      return null;
    }
    try {
      return service.getCurrentRanking();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
