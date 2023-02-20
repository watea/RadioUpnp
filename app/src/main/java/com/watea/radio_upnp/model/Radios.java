/*
 * Copyright (c) 2018. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.radio_upnp.model;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.List;
import java.util.Vector;
import java.util.stream.Collectors;

public class Radios extends Vector<Radio> {
  private static final String LOG_TAG = Radios.class.getName();
  private static final String FILE = Radios.class.getSimpleName();
  private final List<Listener> listeners = new Vector<>();
  @NonNull
  private final String fileName;

  public Radios(@NonNull Context context) {
    super();
    fileName = context.getFilesDir().getPath() + "/" + FILE;
    init();
  }

  public void addListener(@NonNull Listener listener) {
    listeners.add(listener);
  }

  public void removeListener(@NonNull Listener listener) {
    listeners.remove(listener);
  }

  @NonNull
  public List<Radio> getPreferred() {
    return stream().filter(Radio::isPreferred).collect(Collectors.toList());
  }

  public synchronized boolean swap(int from, int to) {
    final int max = size() - 1;
    if ((from > max) || (to > max)) {
      return false;
    }
    set(from, set(to, get(from)));
    return tellListeners(true, listener -> listener.onMove(from, to));
  }

  @Override
  public synchronized boolean add(Radio radio) {
    return add(radio, true);
  }

  // o != null
  @Override
  public boolean remove(@Nullable Object o) {
    assert o != null;
    final int index = indexOf(o);
    return tellListeners(super.remove(o) && write(), listener -> listener.onRemove(index));
  }

  @Override
  public Radio remove(int index) {
    final Radio result = super.remove(index);
    tellListeners(write(), listener -> listener.onRemove(index));
    return result;
  }

  // No listener
  @Override
  public synchronized boolean addAll(@NonNull Collection<? extends Radio> c) {
    return super.addAll(c) && write();
  }

  @NonNull
  @Override
  public synchronized String toString() {
    return toJSONObject().toString();
  }

  public synchronized boolean add(@NonNull Radio radio, boolean isToWrite) {
    return tellListeners(
      super.add(radio) && (!isToWrite || write()),
      listener -> listener.onAdd(radio));
  }

  @NonNull
  public synchronized String export() {
    final StringBuilder result = new StringBuilder().append(Radio.EXPORT_HEAD).append("\n");
    forEach(radio -> result.append(radio.export()).append("\n"));
    return result.toString();
  }

  // No listener
  public synchronized boolean modify(@NonNull Radio radio) {
    final int index = indexOf(radio);
    if (index >= 0) {
      set(index, radio);
      return write();
    }
    return false;
  }

  public void setPreferred(@NonNull Radio radio, boolean isPreferred) {
    radio.setIsPreferred(isPreferred);
    tellListeners(modify(radio), listener -> listener.onPreferredChange(radio));
  }

  // radio must be valid
  @NonNull
  public synchronized Radio getRadioFrom(@NonNull Radio radio, int direction) {
    final int size = size();
    assert size > 0;
    final int index = indexOf(radio);
    assert index > 0;
    return get(size + index + direction % size);
  }

  @Nullable
  public synchronized Radio getRadioFrom(@NonNull String id) {
    return stream().filter(radio -> radio.getId().equals(id)).findFirst().orElse(null);
  }

  public synchronized boolean addFrom(@NonNull JSONObject jSONObject) {
    return addFrom(jSONObject, true, true);
  }

  private synchronized boolean addFrom(
    @NonNull JSONObject jSONObject,
    boolean isToWrite,
    boolean avoidDuplicate) {
    JSONArray jSONArray;
    try {
      jSONArray = (JSONArray) jSONObject.get(FILE.toLowerCase());
    } catch (JSONException jSONException) {
      Log.e(LOG_TAG, "addFrom: invalid JSONObject", jSONException);
      return false;
    }
    boolean result = false;
    for (int i = 0; i < jSONArray.length(); i++) {
      try {
        result = addRadioFrom((JSONObject) jSONArray.get(i), isToWrite, avoidDuplicate) || result;
      } catch (JSONException jSONException) {
        Log.e(LOG_TAG, "addFrom: invalid JSONArray member", jSONException);
      }
    }
    return result;
  }

  private boolean addRadioFrom(
    @NonNull JSONObject jSONObject,
    boolean isToWrite,
    boolean avoidDuplicate) {
    boolean result = false;
    try {
      final Radio radio = new Radio(jSONObject);
      if (!avoidDuplicate ||
        stream().map(Radio::getURL).noneMatch(uRL -> radio.getURL().equals(uRL))) {
        result = add(radio, isToWrite);
      }
    } catch (JSONException jSONException) {
      Log.e(LOG_TAG, "addRadioFrom: internal JSON failure", jSONException);
    } catch (MalformedURLException malformedURLException) {
      Log.e(LOG_TAG, "addRadioFrom: internal failure creating radio", malformedURLException);
    }
    return result;
  }

  @NonNull
  private JSONObject toJSONObject() {
    final JSONObject jSONRadios = new JSONObject();
    final JSONArray jSONRadiosArray = new JSONArray();
    try {
      // Init JSON structure
      jSONRadios.put(FILE.toLowerCase(), jSONRadiosArray);
      // Fill
      stream().map(Radio::getJSONObject).forEach(jSONRadiosArray::put);
    } catch (JSONException jSONException) {
      Log.e(LOG_TAG, "toJSONObject: internal failure", jSONException);
    }
    return jSONRadios;
  }

  private void init() {
    String string = null;
    try (final FileInputStream fileInputStream = new FileInputStream(fileName)) {
      final byte[] buffer = new byte[fileInputStream.available()];
      if (fileInputStream.read(buffer) < 0) {
        Log.e(LOG_TAG, "init: internal failure");
      } else {
        string = new String(buffer);
      }
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "init: IOException fired", iOException);
    }
    if (string != null) {
      try {
        if (!addFrom(new JSONObject(string), false, false)) {
          Log.e(LOG_TAG, "init: no valid radio found");
        }
      } catch (JSONException jSONException) {
        Log.e(LOG_TAG, "init: JSONObject can not be read", jSONException);
      }
    }
  }

  private boolean write() {
    try (final FileOutputStream fileOutputStream = new FileOutputStream(fileName)) {
      fileOutputStream.write(toString().getBytes());
      fileOutputStream.flush();
    } catch (IOException iOException) {
      Log.e("TAG", "write: IOException fired", iOException);
      return false;
    }
    return true;
  }

  private boolean tellListeners(boolean test, @NonNull Consumer<Listener> consumer) {
    if (test) {
      listeners.forEach(consumer::accept);
    }
    return test;
  }

  public interface Listener {
    default void onPreferredChange(@NonNull Radio radio) {
    }

    default void onAdd(@NonNull Radio radio) {
    }

    default void onRemove(int index) {
    }

    default void onMove(int from, int to) {
    }
  }
}