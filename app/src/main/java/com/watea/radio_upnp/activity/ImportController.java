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

package com.watea.radio_upnp.activity;

import static com.watea.radio_upnp.service.ExportDevice.EXPORTER_DEVICE_TYPE;
import static com.watea.radio_upnp.service.Exporter.ACTION_GET_EXPORT;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.service.ExportDevice;
import com.watea.radio_upnp.service.Exporter;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.DeviceTypeHeader;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.registry.RegistryListener;
import org.json.JSONArray;
import org.json.JSONException;

public class ImportController {
  private static final String LOG_TAG = ImportController.class.getName();
  private static final int IMPORT_DELAY = 4000; // ms
  private static final DeviceTypeHeader EXPORTER_DEVICE_TYPE_HEADER =
    new DeviceTypeHeader(EXPORTER_DEVICE_TYPE);
  private static final Handler handler = new Handler(Looper.getMainLooper());
  // <HMI assets
  @NonNull
  private final AlertDialog importAlertDialog;
  // />
  @NonNull
  private final MainActivity mainActivity;
  @Nullable
  private final ExportDevice exportDevice;
  @Nullable
  private RemoteDevice remoteDevice;
  // Only last connected Exporter is considered
  private final RegistryListener exportRegistryListener = new DefaultRegistryListener() {
    @Override
    public void remoteDeviceAdded(Registry registry, RemoteDevice remoteDevice) {
      if (isExporter(remoteDevice)) {
        Log.d(LOG_TAG, "Exporter device found: " + remoteDevice);
        ImportController.this.remoteDevice = remoteDevice;
      }
    }

    @Override
    public void remoteDeviceRemoved(Registry registry, RemoteDevice remoteDevice) {
      if (remoteDevice.equals(ImportController.this.remoteDevice)) {
        Log.d(LOG_TAG, "Exporter device removed: " + remoteDevice);
        ImportController.this.remoteDevice = null;
      }
    }
  };

  public ImportController(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
    ExportDevice localExportDevice = null;
    try {
      localExportDevice = new ExportDevice();
    } catch (Exception exception) {
      Log.e(LOG_TAG, "ExportDevice creation failed", exception);
    }
    exportDevice = localExportDevice;
    importAlertDialog = new AlertDialog.Builder(this.mainActivity, R.style.AlertDialogStyle)
      .setTitle(R.string.title_import)
      .setIcon(R.drawable.ic_baseline_exit_to_app_black_24dp)
      .setMessage(R.string.import_message)
      .setPositiveButton(
        R.string.action_go,
        (dialog, which) ->
          handler.postDelayed(this::upnpImport, (remoteDevice == null) ? IMPORT_DELAY : 0))
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.mainActivity.checkNavigationMenu())
      .create();
  }

  private static boolean isExporter(@NonNull RemoteDevice remoteDevice) {
    return remoteDevice.getType().equals(EXPORTER_DEVICE_TYPE);
  }

  @NonNull
  public RegistryListener getRegistryListener() {
    return exportRegistryListener;
  }

  public void showAlertDialog() {
    if (mainActivity.upnpSearch(EXPORTER_DEVICE_TYPE_HEADER)) {
      importAlertDialog.show();
    }
  }

  // Must be called
  public void addExportService(@NonNull Registry registry) {
    // Define only once
    if (!registry.getLocalDevices().contains(exportDevice)) {
      registry.addDevice(exportDevice);
    }
  }

  private void upnpImport() {
    Log.d(LOG_TAG, "upnpImport");
    if (remoteDevice == null) {
      Log.d(LOG_TAG, "upnpImport but no device");
      mainActivity.tell(R.string.import_connection_failed);
      return;
    }
    final AndroidUpnpService androidUpnpService = mainActivity.getAndroidUpnpService();
    if (androidUpnpService == null) {
      mainActivity.tell(R.string.service_not_available);
      return;
    }
    // Build call
    final ActionInvocation<?> actionInvocation = new ActionInvocation<>(
      remoteDevice.findService(Exporter.EXPORTER_SERVICE_ID).getAction(ACTION_GET_EXPORT));
    // Executes asynchronous in the background
    androidUpnpService.getControlPoint().execute(new ActionCallback(actionInvocation) {
      @Override
      public void success(ActionInvocation actionInvocation) {
        Log.d(LOG_TAG, "Export action success");
        final String export = actionInvocation.getOutput(Exporter.EXPORT).toString();
        // Must be called on main thread for thread safety
        handler.post(() -> {
          try {
            mainActivity.tell(mainActivity.getString(
              MainActivity.getRadios().addFrom(new JSONArray(export)) ?
                R.string.import_successful : R.string.import_no_import));
          } catch (JSONException jSONException) {
            Log.e(LOG_TAG, "JSONException exception fired: ", jSONException);
            mainActivity.tell(R.string.import_failed);
          }
        });
      }

      @Override
      public void failure(
        ActionInvocation actionInvocation, UpnpResponse operation, String defaultMsg) {
        Log.d(LOG_TAG, "Export action error: " + defaultMsg);
        mainActivity.tell(R.string.import_action_failed);
      }
    });
  }
}