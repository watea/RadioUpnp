/*
 * Copyright (c) 2024. Stephane Treuchot
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

package com.watea.radio_upnp.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.upnp.Device;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContentProvider {
  private final Map<Radio, String> contentTypes = new HashMap<>();
  private final Map<Device, List<String>> protocolInfos = new HashMap<>();

  @Nullable
  public String getContentType(@NonNull Radio radio) {
    return contentTypes.get(radio);
  }

  // Process in own thread if necessary
  public void fetchContentType(@NonNull Radio radio, @NonNull Runnable callback) {
    if (getContentType(radio) == null) {
      new Thread(() -> {
        final String contentType = new RadioURL(radio.getURL()).getStreamContentType();
        if (contentType != null) {
          contentTypes.put(radio, contentType);
        }
        callback.run();
      }).start();
    } else {
      callback.run();
    }
  }

  // Do nothing if list is empty
  public void putProtocolInfo(@NonNull Device device, @NonNull List<String> list) {
    if (!list.isEmpty()) {
      protocolInfos.put(device, list);
    }
  }

  public boolean hasProtocolInfo(@NonNull Device device) {
    return protocolInfos.containsKey(device);
  }

  @Nullable
  public String getContentType(@NonNull Device device, @NonNull String contentType) {
    final List<String> deviceProtocolInfos = protocolInfos.get(device);
    if (deviceProtocolInfos != null) {
      final Pattern pattern = Pattern.compile("http-get:\\*:(" + contentType + "):.*");
      for (String protocolInfo : deviceProtocolInfos) {
        final Matcher matcher = pattern.matcher(protocolInfo);
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    return null;
  }
}