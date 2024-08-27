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
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;

import java.util.List;
import java.util.function.Supplier;

public abstract class RadiosAdapter<V extends RadiosAdapter.ViewHolder>
  extends RecyclerView.Adapter<V> {
  protected static final int DEFAULT = -1;
  @NonNull
  protected final MainActivity mainActivity;
  @NonNull
  protected final Supplier<List<Radio>> radiosSupplier;
  private final int row;
  @NonNull
  protected List<Radio> radios;

  public RadiosAdapter(
    @NonNull MainActivity mainActivity,
    @NonNull Supplier<List<Radio>> radiosSupplier,
    int row,
    @NonNull RecyclerView recyclerView) {
    this.mainActivity = mainActivity;
    this.radiosSupplier = radiosSupplier;
    this.radios = this.radiosSupplier.get();
    this.row = row;
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

  protected int getIndexOf(@Nullable Radio radio) {
    return (radio == null) ? DEFAULT : radios.indexOf(radio);
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
      radioTextView
        .setCompoundDrawablesRelativeWithIntrinsicBounds(bitmapDrawable, null, null, null);
    }

    protected void setView(@NonNull Radio radio) {
      this.radio = radio;
      setImage(new BitmapDrawable(
        radioTextView.getResources(), MainActivity.iconHalfResize(this.radio.getIcon())));
      radioTextView.setText(this.radio.getName());
    }
  }
}