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

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.service.RadioURL;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;

public class UpnpDevice {
  private static final Handler handler = new Handler(Looper.getMainLooper());
  @Nullable
  private final RemoteDevice remoteDevice;
  @Nullable
  private Bitmap icon = null;

  public UpnpDevice(@Nullable RemoteDevice remoteDevice) {
    this.remoteDevice = remoteDevice;
  }

  @NonNull
  public static String getIdentity(@NonNull Device<?, ?, ?> device) {
    return device.getIdentity().getUdn().getIdentifierString();
  }

  @Nullable
  public Bitmap getIcon() {
    return icon;
  }

  @Nullable
  public String getIdentity() {
    return (remoteDevice == null) ? null : getIdentity(remoteDevice);
  }

  @SuppressWarnings("NullableProblems")
  @Nullable
  @Override
  public String toString() {
    return
      (remoteDevice == null) ? null :
        (remoteDevice.getDetails() != null) &&
          (remoteDevice.getDetails().getFriendlyName() != null) ?
          remoteDevice.getDetails().getFriendlyName() : remoteDevice.getDisplayString();
  }

  public boolean isFullyHydrated() {
    return (remoteDevice != null) && remoteDevice.isFullyHydrated();
  }

  public boolean hasRemoteDevice(@NonNull RemoteDevice remoteDevice) {
    return remoteDevice.equals(this.remoteDevice);
  }

  // Search asynchronous in background
  public void searchIcon(@NonNull Runnable listener) {
    new Thread(() -> {
      Icon largestIcon = null;
      int maxWidth = 0;
      assert remoteDevice != null;
      for (Icon deviceIcon : remoteDevice.getIcons()) {
        final int width = deviceIcon.getWidth();
        if (width > maxWidth) {
          maxWidth = width;
          largestIcon = deviceIcon;
        }
      }
      Bitmap searchedIcon;
      if ((largestIcon != null) &&
        ((searchedIcon = new RadioURL(remoteDevice.normalizeURI(largestIcon.getUri())).getBitmap())
          != null)) {
        icon = searchedIcon;
        handler.post(listener);
      }
    }).start();
  }
}