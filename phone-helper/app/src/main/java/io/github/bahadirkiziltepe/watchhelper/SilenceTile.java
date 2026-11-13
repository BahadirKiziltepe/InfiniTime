package io.github.bahadirkiziltepe.watchhelper;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * A button for the phone's quick panel (the buttons that come down from the top of the screen)
 * that silences the watch and lets it buzz again.
 *
 * <p>Silent means that the watch neither buzzes nor lights up for anything: notifications, calls,
 * the Claude mark, a low battery. What arrives still collects on its notification screen. It is
 * for the hours the watch is not worn, when its buzzing on a table only disturbs.
 *
 * <p>The button is lit while the watch is silent. Android puts it among the buttons that can be
 * added with the panel's pencil; the app's screen can also ask Android to add it.
 */
public class SilenceTile extends TileService {
  /** Has Android look at the button again after the watch was silenced from somewhere else. */
  static void refresh(Context context) {
    try {
      TileService.requestListeningState(context, new ComponentName(context, SilenceTile.class));
    } catch (RuntimeException e) {
      // The button is not in the panel, or Android does not want to hear about it now.
    }
  }

  @Override
  public void onStartListening() {
    super.onStartListening();
    show();
  }

  @Override
  public void onClick() {
    super.onClick();
    Core core = Core.get(this);
    // The app may have been started for this tap alone: the link to the watch has to be up
    core.wake();
    core.setWatchSilent(!core.watchSilent(), "quick panel button");
    show();
  }

  private void show() {
    Tile tile = getQsTile();
    if (tile == null) {
      return;
    }
    boolean silent = Core.get(this).watchSilent();
    tile.setState(silent ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
    tile.setLabel("Watch silent");
    tile.setSubtitle(silent ? "On" : "Off");
    tile.setContentDescription(silent ? "The watch is silent" : "The watch buzzes");
    tile.updateTile();
  }
}
