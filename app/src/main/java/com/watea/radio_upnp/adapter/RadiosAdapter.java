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
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import java.util.List;
import java.util.Vector;

public abstract class RadiosAdapter<V extends RadiosAdapter<?>.ViewHolder>
  extends RecyclerView.Adapter<V> {
  @NonNull
  protected final Listener listener;
  protected final List<Long> radioIds = new Vector<>();
  private final int resource;
  private final int iconSize;
  @Nullable
  protected RadioLibrary radioLibrary = null;
  @NonNull
  private RadioLibrary.Listener radioLibraryListener = new RadioLibrary.Listener() {
  };

  public RadiosAdapter(@NonNull Listener listener, int iconSize, int resource) {
    this.listener = listener;
    this.iconSize = iconSize;
    this.resource = resource;
  }

  @NonNull
  public static Bitmap createScaledBitmap(@NonNull Bitmap bitmap, int size) {
    return Bitmap.createScaledBitmap(bitmap, size, size, true);
  }

  public void unset() {
    if (radioLibrary != null) {
      radioLibrary.removeListener(radioLibraryListener);
    }
  }

  @Override
  public void onBindViewHolder(@NonNull V v, int i) {
    assert radioLibrary != null;
    if (radioLibrary.isOpen()) {
      final Radio radio = radioLibrary.getFrom(radioIds.get(i));
      assert radio != null;
      v.setView(radio);
    }
  }

  @Override
  public int getItemCount() {
    return radioIds.size();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void refresh(boolean isPreferred) {
    radioIds.clear();
    assert radioLibrary != null;
    radioIds.addAll(
      isPreferred ? radioLibrary.getPreferredRadioIds() : radioLibrary.getAllRadioIds());
    notifyDataSetChanged();
    onCountChange(radioIds.isEmpty());
  }

  @NonNull
  protected View getView(@NonNull ViewGroup viewGroup) {
    return LayoutInflater.from(viewGroup.getContext()).inflate(resource, viewGroup, false);
  }

  // Must be called
  protected void set(
    @NonNull RadioLibrary radioLibrary,
    @NonNull RadioLibrary.Listener radioLibraryListener,
    boolean isPreferred) {
    this.radioLibrary = radioLibrary;
    this.radioLibraryListener = radioLibraryListener;
    this.radioLibrary.addListener(radioLibraryListener);
    refresh(isPreferred);
  }

  protected int getIndexOf(@NonNull Long radioId) {
    return radioIds.indexOf(radioId);
  }

  protected int getIndexOf(@NonNull Radio radio) {
    return getIndexOf(radio.getId());
  }

  protected void onCountChange(boolean isEmpty) {
    listener.onCountChange(isEmpty);
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
        radioTextView.getResources(),
        createScaledBitmap(this.radio.getIcon(), iconSize)));
      radioTextView.setText(this.radio.getName());
    }
  }
}