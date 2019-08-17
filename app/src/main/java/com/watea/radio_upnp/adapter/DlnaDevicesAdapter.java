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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.DlnaDevice;

import java.util.Objects;

public class DlnaDevicesAdapter extends ArrayAdapter<DlnaDevice> {
  private static final int ICON_SIZE = 100;
  @NonNull
  private final Context mContext;
  @Nullable
  private String mChosenDlnaDeviceIdentity;

  public DlnaDevicesAdapter(
    @NonNull Context context, int resource, @Nullable String chosenDlnaDeviceIdentity) {
    super(context, resource);
    mContext = context;
    mChosenDlnaDeviceIdentity = chosenDlnaDeviceIdentity;
    clear();
  }

  // Null if device no longer online
  @Nullable
  public String getChosenDlnaDeviceIdentity() {
    for (int i = 0; i < getCount(); i++) {
      String identity = Objects.requireNonNull(getItem(i)).getIdentity();
      if ((identity != null) && identity.equals(mChosenDlnaDeviceIdentity)) {
        return mChosenDlnaDeviceIdentity;
      }
    }
    return null;
  }

  public boolean hasChosenDlnaDevice() {
    return (getChosenDlnaDeviceIdentity() != null);
  }

  public void removeChosenDlnaDevice() {
    mChosenDlnaDeviceIdentity = null;
  }

  public void onSelection(int position) {
    String identity = Objects.requireNonNull(getItem(position)).getIdentity();
    if (identity != null) {
      mChosenDlnaDeviceIdentity = identity.equals(mChosenDlnaDeviceIdentity) ? null : identity;
    }
  }

  @Override
  public void add(@Nullable DlnaDevice object) {
    if (object != null) {
      // Remove dummy device if any
      if ((getCount() > 0) && (Objects.requireNonNull(getItem(0)).getDevice() == null)) {
        super.clear();
      }
      super.add(object);
    }
  }

  @Override
  public void remove(@Nullable DlnaDevice object) {
    super.remove(object);
    if (isEmpty()) {
      // Specific procedure if empty
      clear();
    }
  }

  @Override
  public void clear() {
    super.clear();
    // Dummy device for waiting...
    add(new DlnaDevice(null));
  }

  @NonNull
  @Override
  public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
    ViewHolder viewHolder;
    if (convertView == null) {
      convertView = LayoutInflater.from(
        parent.getContext()).inflate(R.layout.row_dlna_device, parent, false);
      viewHolder = new ViewHolder(convertView);
      convertView.setTag(viewHolder);
    } else {
      viewHolder = (ViewHolder) convertView.getTag();
    }
    // Device is never null
    viewHolder.setView(Objects.requireNonNull(getItem(position)));
    return convertView;
  }

  private class ViewHolder {
    @NonNull
    private final TextView mDlnaDeviceNameTextView;
    @NonNull
    private final ProgressBar mProgressBar;

    private ViewHolder(@NonNull View itemView) {
      mDlnaDeviceNameTextView = itemView.findViewById(R.id.row_dlna_device_name_text_view);
      mProgressBar = itemView.findViewById(R.id.progress_bar);
    }

    private void setView(@NonNull DlnaDevice dlnaDevice) {
      // Dummy device for waiting message
      boolean isWaiting = (dlnaDevice.getDevice() == null);
      if (isWaiting) {
        mDlnaDeviceNameTextView.setText(R.string.device_no_device_yet);
      } else {
        mDlnaDeviceNameTextView.setText(dlnaDevice.toString());
      }
      if (isWaiting || (dlnaDevice.getIcon() == null)) {
        mDlnaDeviceNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
          R.drawable.ic_cast_black_24dp,
          0, 0, 0);
      } else {
        mDlnaDeviceNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
          new BitmapDrawable(
            getContext().getResources(),
            Bitmap.createScaledBitmap(dlnaDevice.getIcon(), ICON_SIZE, ICON_SIZE, false)),
          null, null, null);
      }
      isWaiting = (isWaiting || !dlnaDevice.isFullyHydrated());
      mDlnaDeviceNameTextView.setTextColor(ContextCompat.getColor(mContext,
        (isWaiting ||
          !Objects.requireNonNull(dlnaDevice.getIdentity()).equals(mChosenDlnaDeviceIdentity)) ?
          R.color.colorPrimaryText : R.color.colorPrimary));
      // Note: for some reason, is sometimes not animated...
      mProgressBar.setVisibility(isWaiting ? View.VISIBLE : View.INVISIBLE);
    }
  }
}