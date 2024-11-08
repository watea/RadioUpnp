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

package com.watea.radio_upnp.ssdp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class SsdpResponse {
  @NonNull
  private final Map<String, String> headers;
  @Nullable
  private final byte[] body;
  @NonNull
  private final InetAddress originAddress;
  private final long expiry;
  @NonNull
  private final Type type;

  public SsdpResponse(
    @NonNull Type type,
    @NonNull Map<String, String> headers,
    @Nullable byte[] body,
    long expiry,
    @NonNull InetAddress originAddress) {
    this.type = type;
    this.headers = headers;
    this.body = body;
    this.expiry = expiry;
    this.originAddress = originAddress;
  }

  @NonNull
  public Type getType() {
    return type;
  }

  @SuppressWarnings("unused")
  @Nullable
  public byte[] getBody() {
    return body;
  }

  @NonNull
  public Map<String, String> getHeaders() {
    return new HashMap<>(headers);
  }

  @NonNull
  public InetAddress getOriginAddress() {
    return originAddress;
  }

  public long getExpiry() {
    return expiry;
  }

  public boolean isExpired() {
    return (expiry <= 0) || (new Date().getTime() > expiry);
  }

  @NonNull
  @Override
  public String toString() {
    return "SsdpResponse{" +
      ", headers=" + headers +
      ", body=" + Arrays.toString(body) +
      '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final SsdpResponse that = (SsdpResponse) o;
    if (!headers.equals(that.headers)) return false;
    return Arrays.equals(body, that.body);
  }

  @Override
  public int hashCode() {
    int result = headers.hashCode();
    result = 31 * result + Arrays.hashCode(body);
    return result;
  }

  public enum Type {
    DISCOVERY_RESPONSE, PRESENCE_ANNOUNCEMENT
  }
}