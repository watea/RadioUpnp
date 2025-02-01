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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Radios extends ArrayList<Radio> {
  public static final String MIME_JSON = "application/json";
  public static final String MIME_CSV = "text/csv";
  private static final String LOG_TAG = Radios.class.getSimpleName();
  private static final String FILE = Radios.class.getSimpleName();
  private static final String CR = "\n";
  private static final byte JSON_ARRAY_START = '[';
  private static final byte JSON_ARRAY_END = ']';
  private static final byte[] JSON_ARRAY_COMMA = ",\n" .getBytes();
  private final List<Listener> listeners = new ArrayList<>();
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

  @NonNull
  @Override
  public Radio remove(int index) {
    final Radio result = super.remove(index);
    tellListeners(write(), listener -> listener.onRemove(index));
    return result;
  }

  // o != null
  @Override
  public boolean remove(@Nullable Object o) {
    assert o != null;
    final int index = indexOf(o);
    return tellListeners(super.remove(o) && write(), listener -> listener.onRemove(index));
  }

  @Override
  public synchronized boolean addAll(@NonNull Collection<? extends Radio> c) {
    return tellListeners(super.addAll(c) && write(), listener -> listener.onAddAll(c));
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
    assert index >= 0;
    return get((size + index + direction) % size);
  }

  @Nullable
  public synchronized Radio getRadioFrom(@NonNull String id) {
    return stream().filter(radio -> radio.getId().equals(id)).findFirst().orElse(null);
  }

  public synchronized void write(@NonNull OutputStream outputStream, @NonNull String type)
    throws JSONException, IOException {
    switch (type) {
      case MIME_CSV:
        outputStream.write((Radio.EXPORT_HEAD + CR).getBytes());
        for (final Radio radio : this) {
          outputStream.write((radio.export() + CR).getBytes());
        }
        break;
      case MIME_JSON:
        // Add the leading [
        outputStream.write(JSON_ARRAY_START);
        for (int i = 0; i < size(); i++) {
          outputStream.write(get(i).getJSONObject().toString().getBytes());
          // Add ',' for all elements except the last one
          if (i < size() - 1) {
            outputStream.write(JSON_ARRAY_COMMA);
          }
        }
        // Add the closing ]
        outputStream.write(JSON_ARRAY_END);
        break;
      default:
        // Shall not happen
    }
  }

  public synchronized boolean importFrom(@NonNull InputStream inputStream)
    throws IOException {
    return read(inputStream) && write();
  }

  public synchronized boolean importCsvFrom(@NonNull InputStream inputStream)
    throws IOException {
    return readCsv(inputStream) && write();
  }

  private boolean readCsv(@NonNull InputStream inputStream) throws IOException {
    boolean result = false;
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
      String line;
      // Skip header line
      reader.readLine();
      while ((line = reader.readLine()) != null) {
        final Radio radio = Radio.getRadioFromCsv(line);
        if (radio != null) {
          result = add(radio, false) || result;
        }
      }
    }
    return result;
  }

  private boolean add(@NonNull Radio radio, boolean isToWrite) {
    return tellListeners(
      super.add(radio) && (!isToWrite || write()), listener -> listener.onAdd(radio));
  }

  // Only JSON can be read.
  // True if something is read.
  private boolean read(@NonNull InputStream inputStream) throws IOException {
    final Gson gson = new Gson();
    // Define the type for the parsing
    final Type listType = new TypeToken<List<Map<String, Object>>>() {
    }.getType();
    boolean result = false;
    // Parse JSON file
    try (final InputStreamReader reader = new InputStreamReader(inputStream)) {
      final List<Map<String, Object>> jsonObjects = gson.fromJson(reader, listType);
      for (final Map<String, Object> jsonObject : jsonObjects) {
        result = addRadioFrom(new JSONObject(jsonObject)) || result;
      }
    }
    return result;
  }

  // Write JSON
  private boolean write() {
    try (final FileOutputStream fileOutputStream = new FileOutputStream(fileName)) {
      write(fileOutputStream, MIME_JSON);
    } catch (IOException | JSONException iOException) {
      Log.e(LOG_TAG, "write: internal failure", iOException);
      return false;
    }
    return true;
  }

  // Avoid duplicate radio.
  // No write.
  private boolean addRadioFrom(@NonNull JSONObject jSONObject) {
    boolean result = false;
    try {
      final Radio radio = new Radio(jSONObject);
      if (stream()
        .map(Radio::getURL)
        .noneMatch(uRL -> radio.getURL().toString().equals(uRL.toString()))) {
        result = add(radio, false);
      }
    } catch (JSONException jSONException) {
      Log.e(LOG_TAG, "addRadioFrom: internal JSON failure", jSONException);
    } catch (MalformedURLException malformedURLException) {
      Log.e(LOG_TAG, "addRadioFrom: internal failure creating radio", malformedURLException);
    }
    return result;
  }

  private void init() {
    try (final FileInputStream fileInputStream = new FileInputStream(fileName)) {
      read(fileInputStream);
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "init: IO failure", iOException);
    }
  }

  private boolean tellListeners(boolean test, @NonNull Consumer<Listener> consumer) {
    if (test) {
      listeners.forEach(consumer);
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

    default void onAddAll(@NonNull Collection<? extends Radio> c) {
    }
  }
}