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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.fourthline.cling.model.meta.Device;

public class DlnaDevice {
  @Nullable
  private Device mDevice;
  @Nullable
  private Bitmap mIcon;

  public DlnaDevice(@Nullable Device device) {
    mDevice = device;
  }

  @Nullable
  public Bitmap getIcon() {
    return mIcon;
  }

  public void setIcon(Bitmap icon) {
    mIcon = icon;
  }

  @Nullable
  public Device getDevice() {
    return mDevice;
  }

  public void setDevice(@NonNull Device device) {
    mDevice = device;
  }

  @Override
  public int hashCode() {
    return (mDevice == null) ? -1 : mDevice.hashCode();
  }

  @Override
  public boolean equals(Object object) {
    return
      object instanceof DlnaDevice &&
        ((mDevice == null) && (((DlnaDevice) object).getDevice() == null) ||
          (mDevice != null) && mDevice.equals(((DlnaDevice) object).getDevice()));
  }

  @SuppressWarnings("NullableProblems")
  @Override
  @Nullable
  public String toString() {
    return
      (mDevice == null) ? null :
        (mDevice.getDetails() != null) && (mDevice.getDetails().getFriendlyName() != null) ?
          mDevice.getDetails().getFriendlyName() : mDevice.getDisplayString();
  }

  public boolean isFullyHydrated() {
    return (mDevice != null) && mDevice.isFullyHydrated();
  }
}