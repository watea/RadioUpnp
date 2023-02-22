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
import com.watea.radio_upnp.model.Radios;

import java.util.Collection;
import java.util.List;

public abstract class RadiosAdapter<V extends RadiosAdapter<?>.ViewHolder>
  extends RecyclerView.Adapter<V> {
  private static final int DEFAULT = -1;
  @NonNull
  protected final Listener listener;
  @NonNull
  protected final Radios radios;
  private final int resource;
  @NonNull
  protected List<Radio> filteredRadios;
  private int currentRadioIndex = DEFAULT;
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
  private boolean isPreferred = false;
  @NonNull
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onPreferredChange(@NonNull Radio radio) {
      notifyItemChanged(getIndexOf(radio));
    }

    @Override
    public void onAdd(@NonNull Radio radio) {
      // Don't add to view if preferred switch is activated, as new radio if not preferred
      if (!isPreferred) {
        updateFilteredRadios();
        notifyItemRangeInserted(getIndexOf(radio), 1);
      }
    }

    @Override
    public void onRemove(int index) {
      updateFilteredRadios();
      notifyItemRemoved(index);
    }

    @Override
    public void onMove(int from, int to) {
      updateFilteredRadios();
      notifyItemMoved(from, to);
    }

    @Override
    public void onAddAll(@NonNull Collection<? extends Radio> c) {
      c.forEach(this::onAdd);
    }
  };

  public RadiosAdapter(
    @NonNull Listener listener,
    int resource,
    @NonNull RecyclerView recyclerView) {
    this.radios = MainActivity.getRadios();
    // Default setting
    filteredRadios = radios;
    this.listener = listener;
    this.resource = resource;
    // Adapter shall be defined for RecyclerView
    recyclerView.setAdapter(this);
  }

  public void unset() {
    MainActivity.removeListener(mainActivityListener);
    radios.removeListener(radiosListener);
  }

  @Override
  public void onBindViewHolder(@NonNull V v, int i) {
    v.setView(filteredRadios.get(i));
  }

  @Override
  public int getItemCount() {
    return filteredRadios.size();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void refresh(boolean isPreferred) {
    this.isPreferred = isPreferred;
    updateFilteredRadios();
    notifyDataSetChanged();
  }

  // Must be called
  public void set(boolean isPreferred) {
    MainActivity.addListener(mainActivityListener);
    radios.addListener(radiosListener);
    refresh(isPreferred);
  }

  @NonNull
  protected View getView(@NonNull ViewGroup viewGroup) {
    return LayoutInflater.from(viewGroup.getContext()).inflate(resource, viewGroup, false);
  }

  protected int getIndexOf(@Nullable Radio radio) {
    return (radio == null) ? DEFAULT : filteredRadios.indexOf(radio);
  }

  private void updateFilteredRadios() {
    filteredRadios = isPreferred ? radios.getPreferred() : radios;
    currentRadioIndex = getIndexOf(MainActivity.getCurrentRadio());
    listener.onCountChange(filteredRadios.isEmpty());
  }

  public interface Listener {
    void onClick(@NonNull Radio radio);

    void onCountChange(boolean isEmpty);
  }

  protected class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    protected final TextView radioTextView;
    @NonNull
    protected Radio radio = Radio.DUMMY_RADIO;

    protected ViewHolder(@NonNull View itemView) {
      super(itemView);
      radioTextView = getRadioTextView(itemView);
      radioTextView.setOnClickListener(v -> listener.onClick(radio));
    }

    @NonNull
    protected TextView getRadioTextView(@NonNull View itemView) {
      return (TextView) itemView;
    }

    protected void setImage(@NonNull BitmapDrawable bitmapDrawable) {
      radioTextView
        .setCompoundDrawablesRelativeWithIntrinsicBounds(null, bitmapDrawable, null, null);
    }

    protected void setView(@NonNull Radio radio) {
      this.radio = radio;
      setImage(new BitmapDrawable(
        radioTextView.getResources(), MainActivity.iconHalfResize(this.radio.getIcon())));
      radioTextView.setText(this.radio.getName());
    }

    protected boolean isCurrentRadio() {
      assert radio != Radio.DUMMY_RADIO;
      return (getIndexOf(radio) == currentRadioIndex);
    }
  }
}