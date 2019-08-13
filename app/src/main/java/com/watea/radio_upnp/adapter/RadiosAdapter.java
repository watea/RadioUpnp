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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.RecyclerView;
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
  private final Context mContext;
  private final int mIconSize;
  @NonNull
  private final Listener mListener;
  // Dummy default
  @NonNull
  private List<Long> mRadioIds = new Vector<>();

  public RadiosAdapter(@NonNull Context context, @NonNull Listener listener) {
    mContext = context;
    mListener = listener;
    Configuration configuration = mContext.getResources().getConfiguration();
    // Image size same order as screen size to get reasonable layout
    mIconSize = ((configuration.orientation == Configuration.ORIENTATION_PORTRAIT) ?
      configuration.screenWidthDp : configuration.screenHeightDp) / 2;
  }

  // Content setter, must be called
  public void setRadioIds(@NonNull List<Long> radioIds) {
    mRadioIds = radioIds;
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(
      LayoutInflater.from(parent.getContext()).inflate(R.layout.row_radio, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
    viewHolder.setView(Objects.requireNonNull(mListener.getRadioFromId(mRadioIds.get(position))));
  }

  @Override
  public int getItemCount() {
    return mRadioIds.size();
  }

  public interface Listener {
    @Nullable
    Radio getRadioFromId(@NonNull Long radioId);

    void onRowClick(Radio radio);

    boolean onPreferredClick(@NonNull Long radioId, Boolean isPreferred);
  }

  class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final LinearLayout mLinearLayout;
    @NonNull
    private final TextView mRadioNameView;
    @NonNull
    private final ImageButton mPreferredButton;
    private Radio mRadio;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      mLinearLayout = (LinearLayout) itemView;
      mRadioNameView = itemView.findViewById(R.id.row_radio_name);
      mPreferredButton = itemView.findViewById(R.id.row_radio_preferred_button);
      // Listener on radio
      mRadioNameView.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          mListener.onRowClick(mRadio);
        }
      });
      // Listener on web link
      mRadioNameView.setOnLongClickListener(new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
          Uri webPageUri = mRadio.getWebPageUri();
          if (webPageUri == null) {
            Snackbar.make(v, R.string.no_web_page, Snackbar.LENGTH_LONG).show();
          } else {
            mContext.startActivity(new Intent(Intent.ACTION_VIEW, webPageUri));
          }
          return true;
        }
      });
      // Listener on preferred device icon
      mPreferredButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          if (mListener.onPreferredClick(mRadio.getId(), !mRadio.isPreferred())) {
            // Database updated, update view
            mRadio.togglePreferred();
            setPreferredButton();
          }
        }
      });
    }

    private int getDominantColor(@NonNull Bitmap bitmap) {
      return Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0);
    }

    private void setView(@NonNull Radio radio) {
      mRadio = radio;
      mLinearLayout.setBackgroundColor(getDominantColor(Objects.requireNonNull(mRadio.getIcon())));
      mRadioNameView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        null,
        new BitmapDrawable(
          mContext.getResources(),
          Bitmap.createScaledBitmap(mRadio.getIcon(), mIconSize, mIconSize, false)),
        null,
        null);
      mRadioNameView.setText(mRadio.getName());
      setPreferredButton();
    }

    private void setPreferredButton() {
      mPreferredButton.setImageResource(mRadio.isPreferred() ?
        R.drawable.ic_star_black_24dp : R.drawable.ic_star_border_black_24dp);
    }
  }
}