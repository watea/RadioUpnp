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
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RadiosMainAdapter
  extends RadiosDisplayAdapter<RadiosMainAdapter.ViewHolder>
  implements Consumer<Radio> {
  @NonNull
  private final Consumer<Consumer<Radio>> currentRadioSupplier;
  @Nullable
  private Radio currentRadio = null;

  public RadiosMainAdapter(
    @NonNull Supplier<List<Radio>> radiosSupplier,
    @NonNull RecyclerView recyclerView,
    @NonNull Listener listener,
    @NonNull Consumer<Consumer<Radio>> currentRadioSupplier) {
    super(radiosSupplier, R.layout.row_radio, recyclerView, listener);
    this.currentRadioSupplier = currentRadioSupplier;
    this.currentRadioSupplier.accept(this);
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(getView(parent));
  }

  // New current radio
  @Override
  public void accept(@Nullable Radio radio) {
    notifyItemChanged();
    currentRadio = radio;
    notifyItemChanged();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    currentRadioSupplier.accept(null);
  }

  @SuppressLint("NotifyDataSetChanged")
  @Override
  protected void onPreferredChange() {
    setRadios();
    onCountChange();
    notifyDataSetChanged();
  }

  private void notifyItemChanged() {
    if (currentRadio != null) {
      notifyItemChanged(indexOf(currentRadio));
    }
  }

  public interface Listener extends RadiosDisplayAdapter.Listener {
    boolean onLongClick(@Nullable Uri webPageUri);
  }

  public class ViewHolder extends RadiosDisplayAdapter<?>.ViewHolder {
    private final int backgroundColor;
    private final int windowBackgroundColor;
    private final int textColor;
    private final Context context;

    protected ViewHolder(@NonNull View itemView) {
      super(itemView, R.id.row_radio_name_text_view);
      backgroundColor = getTextViewColor(android.R.attr.colorPrimary);
      textColor = getTextViewColor(android.R.attr.textColorPrimary);
      windowBackgroundColor = getTextViewColor(android.R.attr.windowBackground);
      radioTextView.setOnLongClickListener(
        v -> ((Listener) listener).onLongClick(radio.getWebPageUri()));
      context = itemView.getContext();
    }

    @Override
    protected void setImage(@NonNull BitmapDrawable bitmapDrawable) {
      radioTextView
        .setCompoundDrawablesRelativeWithIntrinsicBounds(
          (getLayout() == MainActivity.Layout.ROW) ? bitmapDrawable : null,
          (getLayout() == MainActivity.Layout.TILE) ? bitmapDrawable : null,
          null,
          null);
    }

    @Override
    protected void setView(@NonNull Radio radio) {
      super.setView(radio);
      // Change background color for current radio
      final int radioBackgroundColor =
        (radio == currentRadio) ? backgroundColor : getDominantColor(this.radio.getIcon());
      radioTextView.setBackgroundColor(radioBackgroundColor);
      radioTextView.setTextColor(
        (ColorContrastChecker.hasSufficientContrast(textColor, radioBackgroundColor) ||
          ColorContrastChecker.isMoreThanHalfTransparent(radioBackgroundColor)) ?
          textColor : windowBackgroundColor);
      final int tileSize = context.getResources().getDimensionPixelSize(R.dimen.tile_size);
      final boolean isTile = (getLayout() == MainActivity.Layout.TILE);
      radioTextView.getLayoutParams().width = isTile ? tileSize : ViewGroup.LayoutParams.MATCH_PARENT;
      radioTextView.getLayoutParams().height = isTile ? tileSize : context.getResources().getDimensionPixelSize(R.dimen.row_height);
    }

    private MainActivity.Layout getLayout() {
      return ((MainActivity) context).getLayout();
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