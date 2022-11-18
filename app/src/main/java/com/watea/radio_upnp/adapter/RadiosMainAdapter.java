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

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

public class RadiosMainAdapter extends RadiosAdapter<RadiosMainAdapter.ViewHolder> {
  private static final int DEFAULT = -1;
  private int currentRadioIndex = DEFAULT;
  @NonNull
  private final RadioLibrary.Listener mainRadioLibraryListener = new RadioLibrary.Listener() {
    @Override
    public void onNewCurrentRadio(@Nullable Radio radio) {
      if (currentRadioIndex != DEFAULT) {
        notifyItemChanged(currentRadioIndex);
      }
      if (radio == null) {
        currentRadioIndex = DEFAULT;
      } else {
        currentRadioIndex = getIndexOf(radio);
        notifyItemChanged(currentRadioIndex);
      }
    }

    @Override
    public void onAdd(@NonNull Long radioId) {
      radioIds.add(radioId);
      notifyItemRangeInserted(getIndexOf(radioId), 1);
      onCountChange(false);
    }
  };

  public RadiosMainAdapter(@NonNull Listener listener, int iconSize) {
    super(listener, iconSize);
  }

  // Must be called
  public void set(@NonNull RadioLibrary radioLibrary, boolean isPreferredRadios) {
    super.set(radioLibrary, mainRadioLibraryListener, isPreferredRadios);
  }

  public interface Listener extends RadiosAdapter.Listener {
    boolean onLongClick(@Nullable Uri webPageUri);
  }

  protected class ViewHolder extends RadiosAdapter<RadiosMainAdapter.ViewHolder>.ViewHolder {
    @NonNull
    private final Drawable defaultBackground;

    protected ViewHolder(@NonNull View itemView) {
      super(itemView);
      defaultBackground = radioTextView.getBackground();
      radioTextView.setOnLongClickListener(
        v -> ((Listener) listener).onLongClick(radio.getWebPageUri()));
    }

    @Override
    protected void setView(@NonNull Radio radio) {
      super.setView(radio);
      assert radioLibrary != null;
      if (radioLibrary.isCurrentRadio(this.radio)) {
        radioTextView.setBackground(defaultBackground);
      } else {
        radioTextView.setBackgroundColor(getDominantColor(this.radio.getIcon()));
      }
    }

    private int getDominantColor(@NonNull Bitmap bitmap) {
      return createScaledBitmap(bitmap, 1).getPixel(0, 0);
    }
  }
}