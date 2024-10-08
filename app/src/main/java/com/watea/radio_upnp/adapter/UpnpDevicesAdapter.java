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

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.upnp.AndroidUpnpService;
import com.watea.radio_upnp.upnp.Device;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class UpnpDevicesAdapter
  extends RecyclerView.Adapter<UpnpDevicesAdapter.ViewHolder>
  implements AndroidUpnpService.Listener {
  private static final String LOG_TAG = UpnpDevicesAdapter.class.getSimpleName();
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Device> devices = new ArrayList<>();
  private final int selectedColor;
  private final View defaultView;
  @NonNull
  private final Listener listener;
  @Nullable
  private String selectedUpnpDeviceIdentity;

  public UpnpDevicesAdapter(
    int selectedColor,
    @NonNull View defaultView,
    @NonNull Listener listener,
    @Nullable String selectedUpnpDeviceIdentity) {
    this.selectedColor = selectedColor;
    this.defaultView = defaultView;
    this.listener = listener;
    this.selectedUpnpDeviceIdentity = selectedUpnpDeviceIdentity;
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
    return new ViewHolder(LayoutInflater
      .from(viewGroup.getContext())
      .inflate(R.layout.row_upnp_device, viewGroup, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int i) {
    viewHolder.setView(devices.get(i));
  }

  @Override
  public int getItemCount() {
    return devices.size();
  }

  @Override
  public void onDeviceAdd(@NonNull Device device) {
    // This device?
    if (addIf(device)) {
      return;
    }
    // Embedded devices?
    final Set<Device> devices = device.getEmbeddedDevices();
    if (!devices.isEmpty()) {
      Log.d(LOG_TAG, "EmbeddedDevices found: " + devices.size());
      //noinspection ResultOfMethodCallIgnored
      devices.stream().anyMatch(this::addIf);
    }
  }

  @Override
  public void onDeviceRemove(@NonNull Device device) {
    Log.d(LOG_TAG, "Device and embedded removed: " + device.getDisplayString());
    // This device?
    handler.post(() -> remove(device));
    // Embedded devices?
    handler.post(() -> device.getEmbeddedDevices().forEach(this::remove));
  }

  @Override
  public void onIcon(@NonNull Device device) {
    if (devices.contains(device)) {
      notifyItemChanged(devices.indexOf(device));
      if (device == getSelectedDevice()) {
        tellSelectedDevice();
      }
    }
  }

  public void removeSelectedUpnpDevice() {
    setSelectedUpnpDevice(null);
  }

  @SuppressLint("NotifyDataSetChanged")
  public void resetRemoteDevices() {
    devices.clear();
    onCountChange(true);
    notifyDataSetChanged();
  }

  @Nullable
  public Bitmap getSelectedUpnpDeviceIcon() {
    final Device device = getSelectedDevice();
    return (device == null) ? null : device.getIcon();
  }

  // Null if device no longer online
  @Nullable
  private Device getSelectedDevice() {
    for (Device device : devices) {
      final String identity = device.getUUID();
      if ((identity != null) && identity.equals(selectedUpnpDeviceIdentity)) {
        return device;
      }
    }
    return null;
  }

  private void onCountChange(boolean isEmpty) {
    defaultView.setVisibility(isEmpty ? View.VISIBLE : View.INVISIBLE);
    tellSelectedDevice();
  }

  private void setSelectedUpnpDevice(@Nullable Device device) {
    final Device selectedDevice = getSelectedDevice();
    if (selectedDevice != null) {
      notifyChange(selectedDevice);
    }
    if (device == null) {
      selectedUpnpDeviceIdentity = null;
    } else {
      selectedUpnpDeviceIdentity = device.getUUID();
      notifyChange(device);
    }
    tellSelectedDevice();
  }

  private void notifyChange(@NonNull Device device) {
    notifyItemChanged(devices.indexOf(device));
  }

  // Returns true if AV_TRANSPORT_SERVICE_ID is found.
  // Add device in this case.
  private boolean addIf(final Device device) {
    if (device.getShortService(UpnpPlayerAdapter.getAvtransportId()) == null) {
      return false;
    } else {
      Log.d(LOG_TAG, "UPnP reader found!");
      handler.post(() -> add(device));
      return true;
    }
  }

  private void add(@NonNull Device device) {
    devices.add(device);
    onCountChange(false);
    notifyItemInserted(devices.indexOf(device));
  }

  private void remove(@NonNull Device device) {
    final int position = devices.indexOf(device);
    if (position >= 0) {
      devices.remove(device);
      onCountChange(devices.isEmpty());
      notifyItemRemoved(position);
    }
  }

  private void tellSelectedDevice() {
    listener.onSelectedDeviceChange(selectedUpnpDeviceIdentity, getSelectedUpnpDeviceIcon());
  }

  public interface Listener {
    void onRowClick(@NonNull Device device, boolean isSelected);

    void onSelectedDeviceChange(@Nullable String deviceIdentity, @Nullable Bitmap icon);
  }

  public class ViewHolder extends RecyclerView.ViewHolder {
    private static final int ICON_SIZE = 100;
    @NonNull
    private final Bitmap castIcon;
    @NonNull
    private final TextView textView;
    private final int defaultColor;
    @Nullable
    private Device device;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      textView = (TextView) itemView;
      textView.setOnClickListener(v -> {
        assert device != null;
        setSelectedUpnpDevice(
          ((selectedUpnpDeviceIdentity == null) ||
            !selectedUpnpDeviceIdentity.equals(device.getUUID())) ?
            device : null);
        listener.onRowClick(device, (getSelectedDevice() != null));
      });
      defaultColor = textView.getCurrentTextColor();
      castIcon = BitmapFactory.decodeResource(textView.getResources(), R.drawable.ic_cast_blue);
    }

    private void setView(@NonNull Device device) {
      this.device = device;
      textView.setText(device.getDisplayString());
      // Icon
      Bitmap bitmap = device.getIcon();
      bitmap = (bitmap == null) ? castIcon : bitmap;
      bitmap = Bitmap.createScaledBitmap(bitmap, ICON_SIZE, ICON_SIZE, true);
      textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        new BitmapDrawable(textView.getResources(), bitmap), null, null, null);
      // Selected item
      textView.setTextColor(
        (selectedUpnpDeviceIdentity != null) && selectedUpnpDeviceIdentity.equals(device.getUUID()) ?
          selectedColor : defaultColor);
    }
  }
}