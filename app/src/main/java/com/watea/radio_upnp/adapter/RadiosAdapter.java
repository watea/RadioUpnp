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
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.util.List;
import java.util.Objects;
import java.util.Vector;

public class RadiosAdapter extends RecyclerView.Adapter<RadiosAdapter.ViewHolder> {
  @NonNull
  private final Context context;
  private final int iconSize;
  @NonNull
  private final Listener listener;
  // Dummy default
  @NonNull
  private List<Long> radioIds = new Vector<>();

  public RadiosAdapter(@NonNull Context context, @NonNull Listener listener, int iconSize) {
    this.context = context;
    this.listener = listener;
    this.iconSize = iconSize;
  }

  // Content setter, must be called
  public void setRadioIds(@NonNull List<Long> radioIds) {
    this.radioIds = radioIds;
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
    viewHolder.setView(Objects.requireNonNull(listener.getRadioFromId(radioIds.get(i))));
  }

  @Override
  public int getItemCount() {
    return radioIds.size();
  }

  public interface Listener {
    @Nullable
    Radio getRadioFromId(@NonNull Long radioId);

    void onRowClick(@NonNull Radio radio);

    boolean onPreferredClick(@NonNull Long radioId, Boolean isPreferred);
  }

  class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final LinearLayout linearLayout;
    @NonNull
    private final TextView radioNameTextView;
    @NonNull
    private final ImageButton preferredImageButton;
    @NonNull
    private Radio radio = Radio.DUMMY_RADIO;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      linearLayout = (LinearLayout) itemView;
      radioNameTextView = itemView.findViewById(R.id.row_radio_name_text_view);
      preferredImageButton = itemView.findViewById(R.id.row_radio_preferred_image_button);
      // Listener on radio
      radioNameTextView.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          listener.onRowClick(radio);
        }
      });
      // Listener on web link
      radioNameTextView.setOnLongClickListener(new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
          Uri webPageUri = radio.getWebPageUri();
          if (webPageUri == null) {
            Snackbar.make(v, R.string.no_web_page, Snackbar.LENGTH_LONG).show();
          } else {
            context.startActivity(new Intent(Intent.ACTION_VIEW, webPageUri));
          }
          return true;
        }
      });
      // Listener on preferred device icon
      preferredImageButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          if (listener.onPreferredClick(radio.getId(), !radio.isPreferred())) {
            // Database updated, update view
            radio.togglePreferred();
            setPreferredButton();
          }
        }
      });
    }

    private int getDominantColor(@NonNull Bitmap bitmap) {
      return Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0);
    }

    private void setView(@NonNull Radio radio) {
      this.radio = radio;
      linearLayout.setBackgroundColor(getDominantColor(this.radio.getIcon()));
      radioNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        null,
        new BitmapDrawable(
          context.getResources(),
          Bitmap.createScaledBitmap(this.radio.getIcon(), iconSize, iconSize, false)),
        null,
        null);
      radioNameTextView.setText(this.radio.getName());
      setPreferredButton();
    }

    private void setPreferredButton() {
      preferredImageButton.setImageResource(radio.isPreferred() ?
        R.drawable.ic_star_black_30dp : R.drawable.ic_star_border_black_30dp);
    }
  }
}