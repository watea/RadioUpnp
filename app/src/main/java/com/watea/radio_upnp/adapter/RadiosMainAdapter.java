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

import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;

import java.util.List;
import java.util.function.Supplier;

public class RadiosMainAdapter extends RadiosDisplayAdapter<RadiosMainAdapter.ViewHolder> {
  public RadiosMainAdapter(
    @NonNull MainActivity mainActivity,
    @NonNull Supplier<List<Radio>> radiosSupplier,
    @NonNull RecyclerView recyclerView,
    @NonNull Listener listener) {
    super(mainActivity, radiosSupplier, R.layout.row_radio, recyclerView, listener);
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(getView(parent));
  }

  public interface Listener extends RadiosDisplayAdapter.Listener {
    boolean onLongClick(@Nullable Uri webPageUri);
  }

  public class ViewHolder extends RadiosDisplayAdapter<?>.ViewHolder {
    private final int backgroundColor;
    private final int windowBackgroundColor;
    private final int textColor;

    protected ViewHolder(@NonNull View itemView) {
      super(itemView, R.id.row_radio_name_text_view);
      backgroundColor = getTextViewColor(android.R.attr.colorPrimary);
      textColor = getTextViewColor(android.R.attr.textColorPrimary);
      windowBackgroundColor = getTextViewColor(android.R.attr.windowBackground);
      radioTextView.setOnLongClickListener(
        v -> ((Listener) listener).onLongClick(radio.getWebPageUri()));
    }

    @Override
    protected void setImage(@NonNull BitmapDrawable bitmapDrawable) {
      radioTextView
        .setCompoundDrawablesRelativeWithIntrinsicBounds(null, bitmapDrawable, null, null);
    }

    @Override
    protected void setView(@NonNull Radio radio) {
      super.setView(radio);
      final int dominantColor = getDominantColor(this.radio.getIcon());
      final int radioBackgroundColor = isCurrentRadio() ? backgroundColor : dominantColor;
      radioTextView.setBackgroundColor(radioBackgroundColor);
      radioTextView.setTextColor(
        (ColorContrastChecker.hasSufficientContrast(textColor, radioBackgroundColor) ||
          ColorContrastChecker.isMoreThanHalfTransparent(radioBackgroundColor)) ?
          textColor : windowBackgroundColor);
    }

    private int getDominantColor(@NonNull Bitmap bitmap) {
      return Radio.createScaledBitmap(bitmap, 1).getPixel(0, 0);
    }

    private int getTextViewColor(int color) {
      try (final TypedArray typedArray = radioTextView
        .getContext().getTheme().obtainStyledAttributes(new int[]{color})) {
        return typedArray.getColor(0, 0);
      }
    }
  }
}