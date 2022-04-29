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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.util.List;
import java.util.Vector;

public class RadiosAdapter extends RecyclerView.Adapter<RadiosAdapter.ViewHolder> {
  private final int iconSize;
  @NonNull
  private final Listener listener;
  @NonNull
  private final Callback callback;
  private final List<Long> radioIds = new Vector<>();

  public RadiosAdapter(@NonNull Listener listener, @NonNull Callback callback, int iconSize) {
    this.listener = listener;
    this.callback = callback;
    this.iconSize = iconSize;
  }

  // Content setter, must be called
  // null for refresh only
  @SuppressLint("NotifyDataSetChanged")
  public void onRefresh(@Nullable List<Long> radioIds) {
    if (radioIds != null) {
      this.radioIds.clear();
      this.radioIds.addAll(radioIds);
    }
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
    return new ViewHolder(LayoutInflater
      .from(viewGroup.getContext())
      .inflate(R.layout.row_radio, viewGroup, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int i) {
    Radio radio = callback.getFrom(radioIds.get(i));
    if (radio != null) {
      viewHolder.setView(radio);
    }
  }

  @Override
  public int getItemCount() {
    return radioIds.size();
  }

  public interface Listener {
    void onClick(@NonNull Radio radio);

    boolean onLongClick(@Nullable Uri webPageUri);
  }

  public interface Callback {
    @Nullable
    Radio getFrom(@NonNull Long radioId);

    boolean isCurrentRadio(@NonNull Radio radio);
  }

  protected class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final TextView radioTextView;
    @NonNull
    private final Drawable defaultBackground;
    @NonNull
    private Radio radio = Radio.DUMMY_RADIO;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      radioTextView = (TextView) itemView;
      // Search color values
      defaultBackground = radioTextView.getBackground();
      // Listener on radio
      radioTextView.setOnClickListener(v -> listener.onClick(radio));
      // Listener on web link
      radioTextView.setOnLongClickListener(v -> listener.onLongClick(radio.getWebPageUri()));
    }

    private int getDominantColor(@NonNull Bitmap bitmap) {
      return Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0);
    }

    private void setView(@NonNull Radio radio) {
      this.radio = radio;
      if (callback.isCurrentRadio(this.radio)) {
        radioTextView.setBackground(defaultBackground);
      } else {
        radioTextView.setBackgroundColor(getDominantColor(this.radio.getIcon()));
      }
      radioTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        null,
        new BitmapDrawable(
          radioTextView.getResources(),
          Bitmap.createScaledBitmap(this.radio.getIcon(), iconSize, iconSize, true)),
        null,
        null);
      radioTextView.setText(this.radio.getName());
    }
  }
}