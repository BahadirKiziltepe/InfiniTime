package io.github.bahadirkiziltepe.watchhelper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The people the wearer chose to have on the watch, to call them from there. Only these are ever
 * sent to the watch, and only their names; the numbers stay on the phone, in the app's private storage.
 */
final class ContactStore {
  static final int MAX_PEOPLE = 40;
  private static final String PREFS = "watch-contacts";
  private static final String KEY = "people";

  static final class Person {
    final String name;
    final String number;

    Person(String name, String number) {
      this.name = name;
      this.number = number;
    }
  }

  private final SharedPreferences prefs;
  private final List<Person> people = new ArrayList<>();
  private int generation = 1;

  ContactStore(Context app) {
    prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    try {
      JSONArray saved = new JSONArray(prefs.getString(KEY, "[]"));
      for (int i = 0; i < saved.length() && people.size() < MAX_PEOPLE; i++) {
        JSONObject p = saved.optJSONObject(i);
        if (p != null && !p.optString("number", "").isEmpty()) {
          people.add(new Person(p.optString("name", ""), p.optString("number", "")));
        }
      }
    } catch (JSONException e) {
      // Starts empty.
    }
  }

  List<Person> all() {
    return Collections.unmodifiableList(people);
  }

  /** Names the list as it is now. It changes whenever the list does, so the watch can tell an old page from a current one. */
  int generation() {
    return generation;
  }

  /** Null if the person was added; otherwise why not. */
  String add(String name, String number) {
    String cleanNumber = number == null ? "" : number.trim();
    String cleanName = name == null || name.trim().isEmpty() ? cleanNumber : name.trim();
    if (cleanNumber.isEmpty()) {
      return "That contact has no phone number.";
    }
    if (people.size() >= MAX_PEOPLE) {
      return "The watch list is full (" + MAX_PEOPLE + " people). Remove someone first.";
    }
    for (Person p : people) {
      if (digits(p.number).equals(digits(cleanNumber))) {
        return p.name + " is already on the list.";
      }
    }
    people.add(new Person(cleanName, cleanNumber));
    changed();
    return null;
  }

  /** True if this number is on the list, however it is spelled. */
  boolean has(String number) {
    String wanted = digits(number == null ? "" : number);
    if (wanted.isEmpty()) {
      return false;
    }
    for (Person p : people) {
      if (digits(p.number).equals(wanted)) {
        return true;
      }
    }
    return false;
  }

  void remove(int index) {
    if (index >= 0 && index < people.size()) {
      people.remove(index);
      changed();
    }
  }

  /** Moves a person one place up the list. */
  void moveUp(int index) {
    if (index > 0 && index < people.size()) {
      Collections.swap(people, index, index - 1);
      changed();
    }
  }

  private void changed() {
    generation = generation % 250 + 1;
    JSONArray out = new JSONArray();
    try {
      for (Person p : people) {
        out.put(new JSONObject().put("name", p.name).put("number", p.number));
      }
    } catch (JSONException e) {
      return;
    }
    prefs.edit().putString(KEY, out.toString()).apply();
  }

  static String digits(String number) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < number.length(); i++) {
      char c = number.charAt(i);
      if (c >= '0' && c <= '9') {
        sb.append(c);
      }
    }
    // The last ten digits tell two spellings of one number apart from two numbers.
    return sb.length() > 10 ? sb.substring(sb.length() - 10) : sb.toString();
  }
}
