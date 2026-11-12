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
