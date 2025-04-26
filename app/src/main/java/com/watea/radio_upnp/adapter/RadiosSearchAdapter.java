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
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class RadiosSearchAdapter extends RadiosAdapter<RadiosSearchAdapter.ViewHolder> {
  private final Set<Radio> selectedRadios = new HashSet<>();

  public RadiosSearchAdapter(@NonNull RecyclerView recyclerView) {
    super(ArrayList::new, R.layout.row_search_radio, recyclerView);
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
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(getView(parent));
  }

  @NonNull
  public Set<Radio> getSelectedRadios() {
    return selectedRadios;
  }

  @SuppressLint("NotifyDataSetChanged")
  public void selectAll() {
    selectedRadios.addAll(radios);
    notifyDataSetChanged();
  }

  public class ViewHolder extends RadiosAdapter.ViewHolder {
    @NonNull
    private final CheckBox checkBox;

    protected ViewHolder(@NonNull View itemView) {
      super(itemView, R.id.row_search_radio_text_view);
      checkBox = itemView.findViewById(R.id.check_image_button);
      checkBox.setOnClickListener(view -> {
        if (checkBox.isChecked()) {
          selectedRadios.add(radio);
        } else {
          selectedRadios.remove(radio);
        }
      });
    }

    @Override
    protected void setView(@NonNull Radio radio) {
      super.setView(radio);
      checkBox.setChecked(selectedRadios.contains(radio));
    }
  }
}