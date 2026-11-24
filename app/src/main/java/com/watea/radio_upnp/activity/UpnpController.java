/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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

package com.watea.radio_upnp.activity;

import static android.content.Context.BIND_AUTO_CREATE;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.UiUtils;
import com.watea.radio_upnp.adapter.UpnpDevicesAdapter;
import com.watea.radio_upnp.cast.CastManager;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.AndroidUpnpService;
import com.watea.radio_upnp.upnp.Device;

import java.util.function.Consumer;

class UpnpController {
  private static final String LOG_TAG = UpnpController.class.getSimpleName();
  @NonNull
  private final MainActivity mainActivity;
  @NonNull
  private final PlayerController playerController;
  @NonNull
  private final UpnpDevicesAdapter upnpDevicesAdapter;
  @NonNull
  private final AlertDialog alertDialog;
  private final AndroidUpnpService.Listener androidUpnpServiceListener = new AndroidUpnpService.Listener() {
    @Override
    public void onFatalError() {
      mainActivity.runSafelyOnUiThread(() -> mainActivity.tell(R.string.upnp_error));
    }

    @Override
    public void onDeviceAdd(@NonNull Device device) {
      if ((upnpService != null) && (upnpService.getSelectedDevice() == device)) {
        onNewSelectedDevice(device);
      }
    }

    @Override
    public void onDeviceRemove(@NonNull Device device) {
      if ((upnpService != null) && (upnpService.getSelectedDevice() == device)) {
        onNewSelectedDevice(null);
      }
    }

    @Override
    public void onSelectedDeviceChange(@Nullable Device previousDevice, @Nullable Device device) {
      onNewSelectedDevice(device);
    }
  };
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
      upnpService = (AndroidUpnpService.UpnpService) service;
      if (upnpService != null) {
        // Set listeners
        upnpDevicesAdapter.setUpnpService(upnpService);
        upnpService.addListener(androidUpnpServiceListener);
        upnpService.search();
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      if (upnpService != null) {
        // Clear UPnP stuff
        upnpService.clearListeners();
        upnpService = null;
      }
      // No more devices
      upnpDevicesAdapter.setUpnpService(null);
    }
  };
  @Nullable
  private AndroidUpnpService.UpnpService upnpService = null;
  @Nullable
  private Consumer<Bitmap> iconConsumer = null;

  @SuppressLint("InflateParams")
  UpnpController(@NonNull MainActivity mainActivity, @NonNull PlayerController playerController) {
    this.mainActivity = mainActivity;
    this.playerController = playerController;
    final AlertDialog.Builder builder = new AlertDialog.Builder(mainActivity);
    final View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.view_upnp, null);
    final RecyclerView devicesRecyclerView = view.findViewById(R.id.devices_recycler_view);
    devicesRecyclerView.setLayoutManager(new LinearLayoutManager(mainActivity));
    upnpDevicesAdapter = new UpnpDevicesAdapter(
      UiUtils.getThemeAttributeColor(mainActivity, android.R.attr.textColorHighlight),
      view.findViewById(R.id.devices_default_linear_layout),
      this::onDeviceSelected);
    devicesRecyclerView.setAdapter(upnpDevicesAdapter);
    alertDialog = builder
      .setView(view)
      .create();
  }

  void onActivityStart() {
    if (!mainActivity.bindService(new Intent(mainActivity, AndroidUpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
  }

  void onActivityStop() {
    mainActivity.unbindService(upnpConnection);
    // Force disconnection to release resources
    upnpConnection.onServiceDisconnected(null);
  }

  void showDialog() {
    if (CastManager.getInstance().hasCastSession()) {
      mainActivity.tell(R.string.cast_already_running);
    } else {
      alertDialog.show();
    }
  }

  void setIconConsumer(@Nullable Consumer<Bitmap> iconConsumer) {
    this.iconConsumer = iconConsumer;
    // Update icon if needed
    if (upnpService != null) {
      onNewSelectedDevice(upnpService.getSelectedDevice());
    }
  }

  void resetSelectedDevice() {
    if (upnpService == null) {
      Log.e(LOG_TAG, "resetSelectedDevice: internal failure, upnpService not defined");
      return;
    }
    upnpService.setSelectedDeviceIdentity(null);
  }

  private void onDeviceSelected(@Nullable Device device) {
    if (device == null) {
      mainActivity.tell(R.string.no_dlna_selection);
    } else {
      mainActivity.tell(mainActivity.getString(R.string.dlna_selection) + device.getDisplayString());
      final Radio radio = playerController.getCurrentRadio();
      if (radio != null) {
        playerController.startReading(radio);
      }
    }
    alertDialog.dismiss();
  }

  private void onNewSelectedDevice(@Nullable Device device) {
    mainActivity.runSafelyOnUiThread(() -> {
      if (iconConsumer != null) {
        if (device == null) {
          iconConsumer.accept(null);
        } else {
          final Bitmap icon = device.getIcon();
          iconConsumer.accept((icon == null) ? MainActivity.getDefaultIcon(mainActivity, R.drawable.ic_cast_warm) : icon);
        }
      }
    });
  }
}