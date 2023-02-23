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
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public abstract class RadiosDisplayAdapter<V extends RadiosDisplayAdapter<?>.ViewHolder>
  extends RadiosAdapter<V> {
  @NonNull
  protected final Listener listener;
  private int currentRadioIndex = DEFAULT;
  @NonNull
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onPreferredChange(@NonNull Radio radio) {
      notifyItemChanged(getIndexOf(radio));
    }

    @Override
    public void onAdd(@NonNull Radio radio) {
      update();
      final int index = getIndexOf(radio);
      if (index != DEFAULT) {
        notifyItemRangeInserted(index, 1);
      }
    }

    @Override
    public void onRemove(int index) {
      update();
      notifyItemRemoved(index);
    }

    @Override
    public void onMove(int from, int to) {
      update();
      notifyItemMoved(from, to);
    }

    @Override
    public void onAddAll(@NonNull Collection<? extends Radio> c) {
      c.forEach(this::onAdd);
    }
  };
  @NonNull
  private final MainActivity.Listener mainActivityListener = new MainActivity.Listener() {
    @Override
    public void onNewCurrentRadio(@Nullable Radio radio) {
      final int previousCurrentRadioIndex = currentRadioIndex;
      currentRadioIndex = getIndexOf(radio);
      notifyItemChanged(previousCurrentRadioIndex);
      notifyItemChanged(currentRadioIndex);
    }
  };

  public RadiosDisplayAdapter(
    @NonNull Supplier<List<Radio>> radiosSupplier,
    int row,
    @NonNull RecyclerView recyclerView,
    @NonNull Listener listener) {
    super(radiosSupplier, row, recyclerView);
    this.listener = listener;
  }

  public void unset() {
    MainActivity.removeListener(mainActivityListener);
    MainActivity.getRadios().removeListener(radiosListener);
  }

  @SuppressLint("NotifyDataSetChanged")
  public void refresh() {
    radios = radiosSupplier.get();
    update();
    notifyDataSetChanged();
  }

  // Must be called
  public void set() {
    MainActivity.addListener(mainActivityListener);
    MainActivity.getRadios().addListener(radiosListener);
    refresh();
  }

  private void update() {
    currentRadioIndex = getIndexOf(MainActivity.getCurrentRadio());
    listener.onCountChange(radios.isEmpty());
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

    protected boolean isCurrentRadio() {
      assert radio != Radio.DUMMY_RADIO;
      return (getIndexOf(radio) == currentRadioIndex);
    }
  }
}