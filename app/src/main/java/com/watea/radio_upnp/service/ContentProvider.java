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