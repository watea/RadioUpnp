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

import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import java.util.List;
import java.util.function.Supplier;

public abstract class RadiosAdapter<V extends RadiosAdapter.ViewHolder>
  extends RecyclerView.Adapter<V> {
  protected static final int DEFAULT = -1;
  private static final float RADIO_ICON_HEIGHT_RATIO = 0.6f;
  @NonNull
  private final Supplier<List<Radio>> radiosSupplier;
  private final int row;
  @NonNull
  private List<Radio> radios;

  public RadiosAdapter(
    @NonNull Supplier<List<Radio>> radiosSupplier,
    int row,
    @NonNull RecyclerView recyclerView) {
    this.radiosSupplier = radiosSupplier;
    this.row = row;
    radios = radiosSupplier.get();
    // Adapter shall be defined for RecyclerView
    recyclerView.setAdapter(this);
  }

  @Override
  public void onBindViewHolder(@NonNull V v, int i) {
    v.setView(radios.get(i));
  }

  @Override
  public int getItemCount() {
    return radios.size();
  }

  @NonNull
  protected List<Radio> getRadios() {
    return radios;
  }

  protected int indexOf(@NonNull Radio radio) {
    return radios.indexOf(radio);
  }

  protected void setRadios() {
    radios = radiosSupplier.get();
  }

  @NonNull
  protected View getView(@NonNull ViewGroup viewGroup) {
    return LayoutInflater.from(viewGroup.getContext()).inflate(row, viewGroup, false);
  }

  protected abstract static class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    protected final TextView radioTextView;
    @NonNull
    protected Radio radio = Radio.DUMMY_RADIO;

    protected ViewHolder(@NonNull View itemView, int textViewId) {
      super(itemView);
      radioTextView = itemView.findViewById(textViewId);
    }

    // Places the Drawable relatively to radioTextView
    protected void setImage(@NonNull BitmapDrawable bitmapDrawable) {
      radioTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(bitmapDrawable, null, null, null);
    }

    protected void setView(@NonNull Radio radio) {
      this.radio = radio;
      radioTextView.post(() -> {
        // Detached?
        if (radioTextView.getHeight() > 0) {
          setImage(new BitmapDrawable(
            radioTextView.getResources(),
            this.radio.resizeToWidth((int) (radioTextView.getHeight() * RADIO_ICON_HEIGHT_RATIO))));
          radioTextView.setText(this.radio.getName());
        }
      });
    }
  }
}