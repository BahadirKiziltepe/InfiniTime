package io.github.bahadirkiziltepe.watchhelper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * People the app suggests for the watch list: somebody from the phone's contacts called, and they
 * are not on the watch yet. The app's screen shows them under the list with "add" and "dismiss".
 *
 * <p>A suggestion that is dismissed is gone. If the same person calls again, they are suggested
 * again. Names and numbers are kept in the app's private storage, like the watch list itself, and
 * never logged.
 */
final class SuggestionStore {
  static final int MAX = 8;

  private static final String PREFS = "watch-suggestions";
  private static final String KEY_PEOPLE = "people";
  private static final String KEY_SEEN = "seen";
  /** The first time, calls of the last week are looked at. */
  private static final long FIRST_LOOK_BACK_MS = 7 * 86_400_000L;

  private final SharedPreferences prefs;
  private final List<ContactStore.Person> people = new ArrayList<>();
  /** The time of the newest call that has been looked at. */
  private long seen;
  private int generation = 1;

  SuggestionStore(Context app) {
    prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    seen = prefs.getLong(KEY_SEEN, System.currentTimeMillis() - FIRST_LOOK_BACK_MS);
    try {
      JSONArray saved = new JSONArray(prefs.getString(KEY_PEOPLE, "[]"));
      for (int i = 0; i < saved.length() && people.size() < MAX; i++) {
        JSONObject p = saved.optJSONObject(i);
        if (p != null && !p.optString("number", "").isEmpty()) {
          people.add(new ContactStore.Person(p.optString("name", ""), p.optString("number", "")));
        }
      }
    } catch (JSONException e) {
      // Starts empty.
    }
  }

  /** The suggestions, newest caller first. Main thread, like everything here. */
  List<ContactStore.Person> all() {
    return new ArrayList<>(people);
  }

  /** Changes whenever the suggestions do. */
  int generation() {
    return generation;
  }

  /** Calls up to this time have been looked at. */
  long seen() {
    return seen;
  }

  /**
   * Looks at calls that came in ({@code calls}, newest first, with the contacts' names filled in).
   * Whoever has a name, which means they are in the phone's contacts, and is not on the watch list
   * becomes a suggestion. Returns true if the suggestions changed.
   */
  boolean consider(List<CallHistory.Entry> calls, ContactStore contacts) {
    long newest = seen;
    boolean changed = false;
    // Oldest first, so that the newest caller ends up on top
    for (int i = calls.size() - 1; i >= 0; i--) {
      CallHistory.Entry call = calls.get(i);
      if (call.at <= seen) {
        continue;
      }
      newest = Math.max(newest, call.at);
      if (call.name.isEmpty() || call.number.isEmpty() || contacts.has(call.number)) {
        continue;
      }
      forget(call.number);
      people.add(0, new ContactStore.Person(call.name, call.number));
      changed = true;
    }
    while (people.size() > MAX) {
      people.remove(people.size() - 1);
    }
    // Somebody who was put on the watch in the meantime needs no suggesting
    for (int i = people.size() - 1; i >= 0; i--) {
      if (contacts.has(people.get(i).number)) {
        people.remove(i);
        changed = true;
      }
    }
    if (changed || newest != seen) {
      seen = newest;
      save();
    }
    if (changed) {
      generation++;
    }
    return changed;
  }

  /** Takes a suggestion away: it was added to the watch, or the owner does not want it. */
  void dismiss(String number) {
    if (forget(number)) {
      generation++;
      save();
    }
  }

  private boolean forget(String number) {
    boolean any = false;
    String digits = ContactStore.digits(number);
    for (int i = people.size() - 1; i >= 0; i--) {
      if (ContactStore.digits(people.get(i).number).equals(digits)) {
        people.remove(i);
        any = true;
      }
    }
    return any;
  }

  private void save() {
    JSONArray out = new JSONArray();
    try {
      for (ContactStore.Person p : people) {
        out.put(new JSONObject().put("name", p.name).put("number", p.number));
      }
    } catch (JSONException e) {
      return;
    }
    prefs.edit().putString(KEY_PEOPLE, out.toString()).putLong(KEY_SEEN, seen).apply();
  }
}
