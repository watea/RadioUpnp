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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.UpnpDevice;

import org.fourthline.cling.model.meta.RemoteDevice;

import java.util.List;
import java.util.Vector;

public class UpnpDevicesAdapter
  extends RecyclerView.Adapter<UpnpDevicesAdapter.ViewHolder>
  implements UpnpRegistryAdapter.Listener {
  private static final int ICON_SIZE = 100;
  private static final UpnpDevice DUMMY_DEVICE = new UpnpDevice(null);
  private final List<UpnpDevice> upnpDevices = new Vector<>();
  private final int selectedColor;
  @Nullable
  private final Drawable castIcon;
  @NonNull
  private final RowClickListener rowClickListener;
  @Nullable
  private ChosenDeviceListener chosenDeviceListener = null;
  @Nullable
  private String chosenUpnpDeviceIdentity;

  public UpnpDevicesAdapter(
    @Nullable String chosenUpnpDeviceIdentity,
    @NonNull RowClickListener rowClickListener,
    @NonNull Context context) {
    this.chosenUpnpDeviceIdentity = chosenUpnpDeviceIdentity;
    this.rowClickListener = rowClickListener;
    this.selectedColor = ContextCompat.getColor(context, R.color.dark_blue);
    this.castIcon = AppCompatResources.getDrawable(context, R.drawable.ic_cast_blue_24dp);
    onResetRemoteDevices();
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
    viewHolder.setView(upnpDevices.get(i));
  }

  @Override
  public int getItemCount() {
    return upnpDevices.size();
  }

  // Null if device no longer online
  @Nullable
  public UpnpDevice getChosenUpnpDevice() {
    for (UpnpDevice upnpDevice : upnpDevices) {
      String identity = upnpDevice.getIdentity();
      if ((identity != null) && identity.equals(chosenUpnpDeviceIdentity)) {
        return upnpDevice;
      }
    }
    return null;
  }

  private void setChosenUpnpDevice(@Nullable UpnpDevice upnpDevice) {
    chosenUpnpDeviceIdentity = (upnpDevice == null) ? null : upnpDevice.getIdentity();
    notifyChange();
  }

  public void removeChosenUpnpDevice() {
    setChosenUpnpDevice(null);
  }

  // Replace if already here
  @Override
  public void onAddOrReplace(@NonNull RemoteDevice remoteDevice) {
    final UpnpDevice upnpDevice = new UpnpDevice(remoteDevice);
    if (upnpDevices.contains(upnpDevice))
      return;
    // Remove dummy device if any
    if (isWaiting()) {
      upnpDevices.clear();
    }
    upnpDevices.add(upnpDevice);
    notifyChange();
    // Wait for icon (searched asynchronously)
    if (upnpDevice.isFullyHydrated()) {
      upnpDevice.searchIcon(() -> {
        if (upnpDevices.contains(upnpDevice)) {
          notifyItemChanged(upnpDevices.indexOf(upnpDevice));
          if (upnpDevice == getChosenUpnpDevice()) {
            tellChosenDevice();
          }
        }
      });
    }
  }

  @Override
  public void onRemove(@NonNull RemoteDevice remoteDevice) {
    upnpDevices.remove(new UpnpDevice(remoteDevice));
    if (upnpDevices.isEmpty()) {
      setDummyDeviceForWaiting();
    }
    notifyChange();
  }

  @Override
  public void onResetRemoteDevices() {
    upnpDevices.clear();
    setDummyDeviceForWaiting();
    notifyChange();
  }

  @Nullable
  public Bitmap getChosenUpnpDeviceIcon() {
    UpnpDevice upnpDevice = getChosenUpnpDevice();
    return (upnpDevice == null) ? null : upnpDevice.getIcon();
  }

  private void tellChosenDevice() {
    if (chosenDeviceListener != null) {
      chosenDeviceListener.onChosenDeviceChange(getChosenUpnpDeviceIcon());
    }
  }

  @SuppressLint("NotifyDataSetChanged")
  private void notifyChange() {
    tellChosenDevice();
    notifyDataSetChanged();
  }

  private void setDummyDeviceForWaiting() {
    upnpDevices.add(DUMMY_DEVICE);
  }

  private boolean isWaiting() {
    return upnpDevices.contains(DUMMY_DEVICE);
  }

  public interface RowClickListener {
    void onRowClick(@NonNull UpnpDevice upnpDevice, boolean isChosen);
  }

  public interface ChosenDeviceListener {
    void onChosenDeviceChange(@Nullable Bitmap icon);
  }

  protected class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final TextView upnpDeviceNameTextView;
    @NonNull
    private final ProgressBar progressBar;
    @NonNull
    private final View view;
    private final int defaultColor;
    @NonNull
    private UpnpDevice upnpDevice = DUMMY_DEVICE;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      view = itemView;
      view.setOnClickListener(v -> {
        setChosenUpnpDevice(
          ((chosenUpnpDeviceIdentity == null) ||
            !chosenUpnpDeviceIdentity.equals(upnpDevice.getIdentity())) ?
            upnpDevice : null);
        rowClickListener.onRowClick(upnpDevice, (getChosenUpnpDevice() != null));
      });
      upnpDeviceNameTextView = view.findViewById(R.id.row_upnp_device_name_text_view);
      progressBar = view.findViewById(R.id.progress_bar);
      defaultColor = upnpDeviceNameTextView.getCurrentTextColor();
    }

    private void setView(@NonNull UpnpDevice upnpDevice) {
      this.upnpDevice = upnpDevice;
      // Waiting message not accessible
      view.setEnabled(!isWaiting() && upnpDevice.isFullyHydrated());
      // Dummy device for waiting message
      if (isWaiting()) {
        upnpDeviceNameTextView.setText(R.string.device_no_device_yet);
      } else {
        upnpDeviceNameTextView.setText(upnpDevice.toString());
      }
      // Selected item
      upnpDeviceNameTextView.setTextColor(
        (chosenUpnpDeviceIdentity != null) &&
          chosenUpnpDeviceIdentity.equals(upnpDevice.getIdentity()) ?
          selectedColor : defaultColor);
      // Icon
      Bitmap bitmap = upnpDevice.getIcon();
      assert castIcon != null;
      Drawable drawable =
        (bitmap == null) ? castIcon : new BitmapDrawable(view.getResources(), bitmap);
      drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
      upnpDeviceNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        drawable, null, null, null);
      // Wait
      progressBar.setVisibility(view.isEnabled() ? View.INVISIBLE : View.VISIBLE);
    }
  }
}