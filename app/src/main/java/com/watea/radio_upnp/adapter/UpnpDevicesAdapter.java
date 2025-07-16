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
import java.util.Comparator;
import java.util.List;

public class UpnpDevicesAdapter
  extends RecyclerView.Adapter<UpnpDevicesAdapter.ViewHolder>
  implements AndroidUpnpService.Listener {
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Device> devices = new ArrayList<>();
  private final int selectedColor;
  @NonNull
  private final View defaultView;
  @Nullable
  private AndroidUpnpService.UpnpService upnpService = null;

  public UpnpDevicesAdapter(int selectedColor, @NonNull View defaultView) {
    this.selectedColor = selectedColor;
    this.defaultView = defaultView;
  }

  public void setUpnpService(@Nullable AndroidUpnpService.UpnpService upnpService) {
    resetRemoteDevices();
    this.upnpService = upnpService;
    if (this.upnpService != null) {
      this.upnpService.addListener(this);
    }
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
    handler.post(() -> {
      devices.add(device);
      devices.sort(Comparator.comparing(Device::getDisplayString));
      notifyItemInserted(devices.indexOf(device));
      onCountChange(false);
    });
  }

  @Override
  public void onDeviceRemove(@NonNull Device device) {
    handler.post(() -> {
      final int position = devices.indexOf(device);
      if (position >= 0) {
        devices.remove(device);
        notifyItemRemoved(position);
        onCountChange(devices.isEmpty());
      }
    });
  }

  @Override
  public void onSelectedDeviceChange(@Nullable Device previousDevice, @Nullable Device device) {
    notifyChange(previousDevice);
    notifyChange(device);
  }

  @SuppressLint("NotifyDataSetChanged")
  private void resetRemoteDevices() {
    devices.clear();
    notifyDataSetChanged();
    onCountChange(true);
  }

  private void onCountChange(boolean isEmpty) {
    defaultView.setVisibility(isEmpty ? View.VISIBLE : View.INVISIBLE);
  }

  private void notifyChange(@Nullable Device device) {
    if ((device != null) && devices.contains(device)) {
      notifyItemChanged(devices.indexOf(device));
    }
  }

  public class ViewHolder extends RecyclerView.ViewHolder {
    private static final int ICON_SIZE = 100;
    @NonNull
    private final Bitmap castIcon;
    @NonNull
    private final TextView textView;
    private final int defaultColor;
    @Nullable
    private Device device = null;

    protected ViewHolder(@NonNull View itemView) {
      super(itemView);
      textView = (TextView) itemView;
      textView.setOnClickListener(v -> {
        assert device != null;
        assert upnpService != null;
        upnpService.setSelectedDeviceIdentity(isSelected() ? null : device.getUUID());
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
      textView.setTextColor(isSelected() ? selectedColor : defaultColor);
    }

    private boolean isSelected() {
      return (upnpService != null) && (device == upnpService.getSelectedDevice());
    }
  }
}