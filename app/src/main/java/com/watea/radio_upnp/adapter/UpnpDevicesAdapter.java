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
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.upnp.AndroidUpnpService;
import com.watea.radio_upnp.upnp.Device;

import java.util.List;
import java.util.Set;
import java.util.Vector;

public class UpnpDevicesAdapter
  extends RecyclerView.Adapter<UpnpDevicesAdapter.ViewHolder>
  implements AndroidUpnpService.Listener {
  private static final String LOG_TAG = UpnpDevicesAdapter.class.getName();
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Device> devices = new Vector<>();
  @NonNull
  private final Listener listener;
  @Nullable
  private ChosenDeviceListener chosenDeviceListener = null;
  @Nullable
  private String chosenUpnpDeviceIdentity;

  public UpnpDevicesAdapter(
    @Nullable String chosenUpnpDeviceIdentity,
    @NonNull Listener listener) {
    this.chosenUpnpDeviceIdentity = chosenUpnpDeviceIdentity;
    this.listener = listener;
  }

  public void setChosenDeviceListener(@Nullable ChosenDeviceListener chosenDeviceListener) {
    this.chosenDeviceListener = chosenDeviceListener;
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

  // Null if device no longer online
  @Nullable
  public Device getChosenDevice() {
    for (Device device : devices) {
      final String identity = device.getUUID();
      if ((identity != null) && identity.equals(chosenUpnpDeviceIdentity)) {
        return device;
      }
    }
    return null;
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
      if (device == getChosenDevice()) {
        tellChosenDevice();
      }
    }
  }

  public void removeChosenUpnpDevice() {
    setChosenUpnpDevice(null);
  }

  @SuppressLint("NotifyDataSetChanged")
  public void resetRemoteDevices() {
    devices.clear();
    listener.onCountChange(true);
    tellChosenDevice();
    notifyDataSetChanged();
  }

  @Nullable
  public Bitmap getChosenUpnpDeviceIcon() {
    final Device device = getChosenDevice();
    return (device == null) ? null : device.getIcon();
  }

  private void setChosenUpnpDevice(@Nullable Device device) {
    final Device chosenDevice = getChosenDevice();
    if (chosenDevice != null) {
      notifyChange(chosenDevice);
    }
    if (device == null) {
      chosenUpnpDeviceIdentity = null;
    } else {
      chosenUpnpDeviceIdentity = device.getUUID();
      notifyChange(device);
    }
    tellChosenDevice();
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
    listener.onCountChange(false);
    notifyItemInserted(devices.indexOf(device));
  }

  private void remove(@NonNull Device device) {
    final int position = devices.indexOf(device);
    if (position >= 0) {
      devices.remove(device);
      listener.onCountChange(devices.isEmpty());
      notifyItemRemoved(position);
    }
  }

  private void tellChosenDevice() {
    if (chosenDeviceListener != null) {
      chosenDeviceListener.onChosenDeviceChange(getChosenUpnpDeviceIcon());
    }
  }

  public interface Listener {
    void onRowClick(@NonNull Device device, boolean isChosen);

    void onCountChange(boolean isEmpty);
  }

  public interface ChosenDeviceListener {
    void onChosenDeviceChange(@Nullable Bitmap icon);
  }

  public class ViewHolder extends RecyclerView.ViewHolder {
    private static final int ICON_SIZE = 100;
    @NonNull
    private final Bitmap castIcon;
    @NonNull
    private final TextView textView;
    private final int defaultColor;
    private final int selectedColor;
    @Nullable
    private Device device;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      textView = (TextView) itemView;
      textView.setOnClickListener(v -> {
        assert device != null;
        setChosenUpnpDevice(
          ((chosenUpnpDeviceIdentity == null) ||
            !chosenUpnpDeviceIdentity.equals(device.getUUID())) ?
            device : null);
        listener.onRowClick(device, (getChosenDevice() != null));
      });
      defaultColor = textView.getCurrentTextColor();
      selectedColor = ContextCompat.getColor(textView.getContext(), R.color.dark_blue);
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
        (chosenUpnpDeviceIdentity != null) && chosenUpnpDeviceIdentity.equals(device.getUUID()) ?
          selectedColor : defaultColor);
    }
  }
}