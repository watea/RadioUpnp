/*
 * Copyright (c) 2026. Stephane Treuchot
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URL;

public class ConnectionSet {
  @NonNull
  private final URL url;
  @NonNull
  private final String content;
  private final int bitrate; // -1 if unknown

  public ConnectionSet(@NonNull URL url, @NonNull String content, int bitrate) {
    this.url = url;
    this.content = content;
    this.bitrate = bitrate;
  }

  public int getBitrate() {
    return bitrate;
  }

  @NonNull
  public String getContent() {
    return content;
  }

  @NonNull
  public URL getUrl() {
    return url;
  }

  public interface Supplier {
    @Nullable
    ConnectionSet get(@NonNull URL url, @NonNull String lockKey);
  }
}