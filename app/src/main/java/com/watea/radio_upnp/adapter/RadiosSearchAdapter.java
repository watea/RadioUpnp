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
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

public class RadiosSearchAdapter extends RecyclerView.Adapter<RadiosSearchAdapter.ViewHolder> {
  private final List<Radio> radios = new Vector<>();
  private final Set<Radio> selectedRadios = new HashSet<>();

  public RadiosSearchAdapter() {
  }

  @NonNull
  public static Bitmap createScaledBitmap(@NonNull Bitmap bitmap, int size) {
    return Bitmap.createScaledBitmap(bitmap, size, size, true);
  }

  public void add(@NonNull Radio radio) {
    radios.add(radio);
    radios.sort(Comparator.comparing(Radio::getName));
    notifyItemInserted(radios.indexOf(radio));
  }

  @SuppressLint("NotifyDataSetChanged")
  public void clear() {
    radios.clear();
    selectedRadios.clear();
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
    return new RadiosSearchAdapter.ViewHolder(LayoutInflater
      .from(viewGroup.getContext())
      .inflate(R.layout.row_search_radio, viewGroup, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int i) {
    viewHolder.setView(radios.get(i));
  }

  @Override
  public int getItemCount() {
    return radios.size();
  }

  public Set<Radio> getSelectedRadios() {
    return selectedRadios;
  }

  public class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final TextView radioTextView;
    @NonNull
    private final ImageButton checkImageButton;
    @NonNull
    private Radio radio = Radio.DUMMY_RADIO;

    protected ViewHolder(@NonNull View itemView) {
      super(itemView);
      radioTextView = itemView.findViewById(R.id.row_search_radio_text_view);
      checkImageButton = itemView.findViewById(R.id.check_image_button);
      checkImageButton.setOnClickListener(view -> {
        if (isChecked()) {
          selectedRadios.remove(radio);
        } else {
          selectedRadios.add(radio);
        }
        setCheckImageButton();
      });
    }

    protected void setView(@NonNull Radio radio) {
      this.radio = radio;
      radioTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        new BitmapDrawable(
          radioTextView.getResources(),
          createScaledBitmap(this.radio.getIcon(), MainActivity.getSmallIconSize())),
        null,
        null,
        null);
      radioTextView.setText(this.radio.getName());
      setCheckImageButton();
    }

    private boolean isChecked() {
      return selectedRadios.contains(radio);
    }

    private void setCheckImageButton() {
      checkImageButton.setImageResource(isChecked() ?
        R.drawable.ic_baseline_check_box_24dp : R.drawable.ic_baseline_check_box_outline_blank_24dp);
    }
  }
}