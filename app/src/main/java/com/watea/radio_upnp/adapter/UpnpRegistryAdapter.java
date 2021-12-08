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

package com.watea.radio_upnp.adapter;

import static com.watea.radio_upnp.adapter.UpnpPlayerAdapter.AV_TRANSPORT_SERVICE_ID;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

public class UpnpRegistryAdapter extends DefaultRegistryListener {
  private static final String LOG_TAG = UpnpRegistryAdapter.class.getName();
  private final Handler handler = new Handler(Looper.getMainLooper());
  @NonNull
  private final Listener listener;

  public UpnpRegistryAdapter(@NonNull Listener listener) {
    this.listener = listener;
    // Clear listener state
    this.listener.onResetRemoteDevices();
  }

  // Add device if service AV_TRANSPORT_SERVICE_ID is found
  @Override
  public void remoteDeviceAdded(Registry registry, final RemoteDevice remoteDevice) {
    // This device?
    if (!add(remoteDevice)) {
      // Embedded devices?
      RemoteDevice[] remoteDevices = remoteDevice.getEmbeddedDevices();
      if ((remoteDevices != null) && (remoteDevices.length > 0)) {
        Log.d(LOG_TAG, "EmbeddedRemoteDevices found: " + remoteDevices.length);
        for (RemoteDevice embeddedRemoteDevice : remoteDevices) {
          if (add(embeddedRemoteDevice)) {
            return;
          }
        }
      }
    }
  }

  @Override
  public void remoteDeviceRemoved(Registry registry, final RemoteDevice remoteDevice) {
    Log.d(LOG_TAG, "RemoteDevice and embedded removed: " + remoteDevice.getDisplayString());
    // This device?
    handler.post(() -> listener.onRemove(remoteDevice));
    // Embedded devices?
    for (RemoteDevice embeddedRemoteDevice : remoteDevice.getEmbeddedDevices()) {
      handler.post(() -> listener.onRemove(embeddedRemoteDevice));
    }
  }

  // Returns true if AV_TRANSPORT_SERVICE_ID is found
  private boolean add(final RemoteDevice remoteDevice) {
    Log.d(LOG_TAG, "RemoteDevice found: " + remoteDevice.getDisplayString());
    RemoteService[] remoteServices = remoteDevice.getServices();
    Log.d(LOG_TAG, "> RemoteServices found: " + remoteServices.length);
    for (Service<?, ?> service : remoteServices) {
      ServiceId serviceId = service.getServiceId();
      Log.d(LOG_TAG, ">> RemoteService: " + serviceId);
      if (serviceId.equals(AV_TRANSPORT_SERVICE_ID)) {
        Log.d(LOG_TAG, ">>> UPnP reader found!");
        handler.post(() -> listener.onAddOrReplace(remoteDevice));
        return true;
      }
    }
    return false;
  }

  public interface Listener {
    void onAddOrReplace(RemoteDevice remoteDevice);

    void onRemove(RemoteDevice remoteDevice);

    void onResetRemoteDevices();
  }
}