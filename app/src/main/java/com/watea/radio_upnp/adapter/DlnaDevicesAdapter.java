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
import com.watea.radio_upnp.model.DlnaDevice;

import org.fourthline.cling.model.meta.RemoteDevice;

import java.util.List;
import java.util.Vector;

public class DlnaDevicesAdapter
  extends RecyclerView.Adapter<DlnaDevicesAdapter.ViewHolder>
  implements UpnpRegistryAdapter.Listener {
  private static final int ICON_SIZE = 100;
  private static final DlnaDevice DUMMY_DEVICE = new DlnaDevice(null);
  private final List<DlnaDevice> dlnaDevices = new Vector<>();
  @NonNull
  private final Listener listener;
  private final int selectedColor;
  @Nullable
  private final Drawable castIcon;
  @Nullable
  private String chosenDlnaDeviceIdentity;

  public DlnaDevicesAdapter(
    @Nullable String chosenDlnaDeviceIdentity,
    @NonNull Listener listener,
    @NonNull Context context) {
    this.chosenDlnaDeviceIdentity = chosenDlnaDeviceIdentity;
    this.listener = listener;
    this.selectedColor = ContextCompat.getColor(context, R.color.dark_blue);
    this.castIcon = AppCompatResources.getDrawable(context, R.drawable.ic_cast_blue_24dp);
    onResetRemoteDevices();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
    return new ViewHolder(LayoutInflater
      .from(viewGroup.getContext())
      .inflate(R.layout.row_dlna_device, viewGroup, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int i) {
    viewHolder.setView(dlnaDevices.get(i));
  }

  @Override
  public int getItemCount() {
    return dlnaDevices.size();
  }

  // Null if device no longer online
  @Nullable
  public DlnaDevice getChosenDlnaDevice() {
    for (DlnaDevice dlnaDevice : dlnaDevices) {
      String identity = dlnaDevice.getIdentity();
      if ((identity != null) && identity.equals(chosenDlnaDeviceIdentity)) {
        return dlnaDevice;
      }
    }
    return null;
  }

  private void setChosenDlnaDevice(@Nullable DlnaDevice dlnaDevice) {
    chosenDlnaDeviceIdentity = (dlnaDevice == null) ? null : dlnaDevice.getIdentity();
    notifyChange();
  }

  @Nullable
  public Bitmap getChosenDlnaDeviceIcon() {
    DlnaDevice dlnaDevice = getChosenDlnaDevice();
    return (dlnaDevice == null) ? null : dlnaDevice.getIcon();
  }

  public void removeChosenDlnaDevice() {
    setChosenDlnaDevice(null);
  }

  // Replace if already here
  @Override
  public void onAddOrReplace(@NonNull RemoteDevice remoteDevice) {
    final DlnaDevice dlnaDevice = new DlnaDevice(remoteDevice);
    if (dlnaDevices.contains(dlnaDevice))
      return;
    // Remove dummy device if any
    if (isWaiting()) {
      dlnaDevices.clear();
    }
    dlnaDevices.add(dlnaDevice);
    notifyChange();
    // Wait for icon (searched asynchronously)
    if (dlnaDevice.isFullyHydrated()) {
      dlnaDevice.searchIcon(() -> {
        if (dlnaDevices.contains(dlnaDevice)) {
          notifyItemChanged(dlnaDevices.indexOf(dlnaDevice));
          if (dlnaDevice == getChosenDlnaDevice()) {
            listener.onChosenDeviceChange();
          }
        }
      });
    }
  }

  @Override
  public void onRemove(@NonNull RemoteDevice remoteDevice) {
    dlnaDevices.remove(new DlnaDevice(remoteDevice));
    if (dlnaDevices.isEmpty()) {
      setDummyDeviceForWaiting();
    }
    notifyChange();
  }

  @Override
  public void onResetRemoteDevices() {
    dlnaDevices.clear();
    setDummyDeviceForWaiting();
    notifyChange();
  }

  @SuppressLint("NotifyDataSetChanged")
  private void notifyChange() {
    listener.onChosenDeviceChange();
    notifyDataSetChanged();
  }

  private void setDummyDeviceForWaiting() {
    dlnaDevices.add(DUMMY_DEVICE);
  }

  private boolean isWaiting() {
    return dlnaDevices.contains(DUMMY_DEVICE);
  }

  public interface Listener {
    void onRowClick(@NonNull DlnaDevice dlnaDevice, boolean isChosen);

    void onChosenDeviceChange();
  }

  protected class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final TextView dlnaDeviceNameTextView;
    @NonNull
    private final ProgressBar progressBar;
    @NonNull
    private final View view;
    private final int defaultColor;
    @NonNull
    private DlnaDevice dlnaDevice = DUMMY_DEVICE;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      view = itemView;
      view.setOnClickListener(v -> {
        setChosenDlnaDevice(
          ((chosenDlnaDeviceIdentity == null) ||
            !chosenDlnaDeviceIdentity.equals(dlnaDevice.getIdentity())) ?
            dlnaDevice : null);
        listener.onRowClick(dlnaDevice, (getChosenDlnaDevice() != null));
      });
      dlnaDeviceNameTextView = view.findViewById(R.id.row_dlna_device_name_text_view);
      progressBar = view.findViewById(R.id.progress_bar);
      defaultColor = dlnaDeviceNameTextView.getCurrentTextColor();
    }

    private void setView(@NonNull DlnaDevice dlnaDevice) {
      this.dlnaDevice = dlnaDevice;
      // Waiting message not accessible
      view.setEnabled(!isWaiting() && dlnaDevice.isFullyHydrated());
      // Dummy device for waiting message
      if (isWaiting()) {
        dlnaDeviceNameTextView.setText(R.string.device_no_device_yet);
      } else {
        dlnaDeviceNameTextView.setText(dlnaDevice.toString());
      }
      // Selected item
      dlnaDeviceNameTextView.setTextColor(
        (chosenDlnaDeviceIdentity != null) &&
          chosenDlnaDeviceIdentity.equals(dlnaDevice.getIdentity()) ?
          selectedColor : defaultColor);
      // Icon
      Bitmap bitmap = dlnaDevice.getIcon();
      assert castIcon != null;
      Drawable drawable =
        (bitmap == null) ? castIcon : new BitmapDrawable(view.getResources(), bitmap);
      drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
      dlnaDeviceNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        drawable, null, null, null);
      // Wait
      progressBar.setVisibility(view.isEnabled() ? View.INVISIBLE : View.VISIBLE);
    }
  }
}