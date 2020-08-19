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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.service.NetworkTester;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.util.Objects;

public class DlnaDevice {
  @Nullable
  private RemoteDevice remoteDevice;
  @Nullable
  private Bitmap icon = null;

  public DlnaDevice(@Nullable RemoteDevice remoteDevice) {
    this.remoteDevice = remoteDevice;
  }

  public static String getIdentity(@NonNull Device<?, ?, ?> device) {
    return device.getIdentity().getUdn().getIdentifierString();
  }

  @Nullable
  public Bitmap getIcon() {
    return icon;
  }

  public void setIcon(@NonNull Bitmap icon) {
    this.icon = icon;
  }

  @Nullable
  public String getIdentity() {
    return (remoteDevice == null) ? null : getIdentity(remoteDevice);
  }

  @Override
  public boolean equals(Object object) {
    return
      object instanceof DlnaDevice &&
        ((remoteDevice == null) && (((DlnaDevice) object).remoteDevice == null) ||
          (remoteDevice != null) && remoteDevice.equals(((DlnaDevice) object).remoteDevice));
  }

  @SuppressWarnings("NullableProblems")
  @Override
  @Nullable
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

  public void searchIcon(@NonNull final Listener listener) {
    if (isFullyHydrated()) {
      new Thread() {
        @Override
        public void run() {
          Icon largestIcon = null;
          int maxWidth = 0;
          for (Icon deviceIcon : Objects.requireNonNull(remoteDevice).getIcons()) {
            int width = deviceIcon.getWidth();
            if (width > maxWidth) {
              maxWidth = width;
              largestIcon = deviceIcon;
            }
          }
          if (largestIcon != null) {
            Bitmap searchedIcon =
              NetworkTester.getBitmapFromUrl(remoteDevice.normalizeURI(largestIcon.getUri()));
            if (searchedIcon != null) {
              icon = searchedIcon;
              listener.onNewIcon();
            }
          }
        }
      }.start();
    }
  }

  public interface Listener {
    void onNewIcon();
  }
}