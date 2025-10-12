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

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public abstract class RadiosDisplayAdapter<V extends RadiosDisplayAdapter<?>.ViewHolder>
  extends RadiosAdapter<V> {
  @NonNull
  protected final Listener listener;
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onChange(@NonNull Radio radio) {
      notifyItemChanged(indexOf(radio));
    }

    @Override
    public void onAdd(@NonNull Radio radio) {
      final int index = indexOf(radio);
      if (index != DEFAULT) {
        onCountChange();
        notifyItemRangeInserted(index, 1);
      }
    }

    @Override
    public void onRemove(int index) {
      onCountChange();
      notifyItemRemoved(index);
    }

    @Override
    public void onMove(int from, int to) {
      notifyItemMoved(from, to);
    }

    @Override
    public void onAddAll(@NonNull Collection<? extends Radio> c) {
      c.forEach(this::onAdd);
    }

    @Override
    public void onPreferredChange() {
      RadiosDisplayAdapter.this.onPreferredChange();
    }
  };

  public RadiosDisplayAdapter(
    @NonNull Supplier<List<Radio>> radiosSupplier,
    int row,
    @NonNull RecyclerView recyclerView,
    @NonNull Listener listener) {
    super(radiosSupplier, row, recyclerView);
    this.listener = listener;
    Radios.getInstance().addListener(radiosListener);
    // Init listener
    onCountChange();
  }

  // Must be called
  public void onDestroy() {
    Radios.getInstance().removeListener(radiosListener);
  }

  protected void onPreferredChange() {
  }

  protected void onCountChange() {
    listener.onCountChange(getRadios().isEmpty());
  }

  public interface Listener {
    void onClick(@NonNull Radio radio);

    void onCountChange(boolean isEmpty);
  }

  protected abstract class ViewHolder extends RadiosAdapter.ViewHolder {
    protected ViewHolder(@NonNull View itemView, int textViewId) {
      super(itemView, textViewId);
      radioTextView.setOnClickListener(v -> listener.onClick(radio));
    }
  }
}