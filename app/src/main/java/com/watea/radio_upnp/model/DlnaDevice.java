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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.fourthline.cling.model.meta.Device;

public class DlnaDevice {
  @Nullable
  private Device<?, ?, ?> device;
  @Nullable
  private Bitmap icon = null;

  public DlnaDevice(@Nullable Device<?, ?, ?> device) {
    this.device = device;
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
    return (device == null) ? null : getIdentity(device);
  }

  @Override
  public boolean equals(Object object) {
    return
      object instanceof DlnaDevice &&
        ((device == null) && (((DlnaDevice) object).device == null) ||
          (device != null) && device.equals(((DlnaDevice) object).device));
  }

  @SuppressWarnings("NullableProblems")
  @Override
  @Nullable
  public String toString() {
    return
      (device == null) ? null :
        (device.getDetails() != null) && (device.getDetails().getFriendlyName() != null) ?
          device.getDetails().getFriendlyName() : device.getDisplayString();
  }

  public boolean isFullyHydrated() {
    return (device != null) && device.isFullyHydrated();
  }
}