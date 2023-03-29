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

package com.watea.radio_upnp.service;

import android.util.Log;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.activity.MainActivity;

import org.fourthline.cling.binding.annotations.UpnpAction;
import org.fourthline.cling.binding.annotations.UpnpOutputArgument;
import org.fourthline.cling.binding.annotations.UpnpService;
import org.fourthline.cling.binding.annotations.UpnpServiceId;
import org.fourthline.cling.binding.annotations.UpnpServiceType;
import org.fourthline.cling.binding.annotations.UpnpStateVariable;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDAServiceId;

@UpnpService(
  serviceId = @UpnpServiceId(Exporter.EXPORTER_SERVICE),
  serviceType = @UpnpServiceType(value = Exporter.EXPORTER_SERVICE))
public class Exporter {
  public static final String EXPORTER_SERVICE = "Exporter";
  public static final ServiceId EXPORTER_SERVICE_ID = new UDAServiceId(EXPORTER_SERVICE);
  public static final String ACTION_GET_EXPORT = "GetExport";
  public static final String EXPORT = "Export";
  private static final String LOG_TAG = Exporter.class.getName();

  @UpnpStateVariable(sendEvents = false)
  private String export = "";

  @NonNull
  @UpnpAction(out = @UpnpOutputArgument(name = EXPORT))
  public String getExport() {
    export = MainActivity.getRadios().toString();
    Log.d(LOG_TAG, "getExport: " + export.length() + " characters");
    return export;
  }
}