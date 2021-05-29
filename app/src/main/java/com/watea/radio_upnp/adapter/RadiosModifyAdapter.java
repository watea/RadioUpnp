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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.util.List;
import java.util.Objects;
import java.util.Vector;

public class RadiosModifyAdapter extends RecyclerView.Adapter<RadiosModifyAdapter.ViewHolder> {
  @NonNull
  private final Context context;
  private final int iconSize;
  @NonNull
  private final Listener listener;
  // Dummy default
  @NonNull
  private List<Long> radioIds = new Vector<>();

  public RadiosModifyAdapter(@NonNull Context context, @NonNull Listener listener, int iconSize) {
    this.context = context;
    this.listener = listener;
    this.iconSize = iconSize;
  }

  // Shall be called
  public void attachToRecyclerView(@NonNull RecyclerView recyclerView) {
    new ItemTouchHelper(new RadioItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
  }

  // Content setter, must be called
  public void setRadioIds(@NonNull List<Long> radioIds) {
    this.radioIds = radioIds;
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(
      LayoutInflater.from(parent.getContext()).inflate(R.layout.row_modify_radio, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
    viewHolder.setView(Objects.requireNonNull(listener.getRadioFromId(radioIds.get(position))));
  }

  @Override
  public int getItemCount() {
    return radioIds.size();
  }

  public interface Listener {
    @Nullable
    Radio getRadioFromId(@NonNull Long radioId);

    void onModifyClick(@NonNull Radio radio);

    boolean onDelete(@NonNull Long radioId);

    boolean onMove(@NonNull Long fromRadioId, @NonNull Long toRadioId);
  }

  private class RadioItemTouchHelperCallback extends ItemTouchHelper.Callback {
    @Override
    public int getMovementFlags(
      @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
      int swipeFlags = ItemTouchHelper.START | ItemTouchHelper.END;
      return makeMovementFlags(dragFlags, swipeFlags);
    }

    @Override
    public boolean onMove(
      @NonNull RecyclerView recyclerView,
      @NonNull RecyclerView.ViewHolder viewHolder,
      @NonNull RecyclerView.ViewHolder targetViewHolder) {
      int from = viewHolder.getAbsoluteAdapterPosition();
      Long fromId = radioIds.get(from);
      int to = targetViewHolder.getAbsoluteAdapterPosition();
      Long toId = radioIds.get(to);
      if (listener.onMove(fromId, toId)) {
        // Database updated, update view
        radioIds.set(to, fromId);
        radioIds.set(from, toId);
        notifyItemMoved(from, to);
        return true;
      }
      return false;
    }

    @Override
    public boolean isLongPressDragEnabled() {
      return true;
    }

    @Override
    public boolean isItemViewSwipeEnabled() {
      return true;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
      int position = viewHolder.getAbsoluteAdapterPosition();
      if (listener.onDelete(radioIds.get(position))) {
        // Database updated, update view
        radioIds.remove(position);
        notifyItemRemoved(position);
      }
    }
  }

  protected class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final TextView radioNameTextView;
    @Nullable
    private Radio radio;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      radioNameTextView = itemView.findViewById(R.id.row_modify_radio_name_text_view);
      // Edit action
      itemView.findViewById(R.id.row_modify_radio_name_text_view).setOnClickListener(
        view -> listener.onModifyClick(radio));
    }

    private void setView(@NonNull Radio radio) {
      this.radio = radio;
      radioNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        new BitmapDrawable(
          context.getResources(),
          Bitmap.createScaledBitmap(this.radio.getIcon(), iconSize, iconSize, false)),
        null,
        null,
        null);
      radioNameTextView.setText(this.radio.getName());
    }
  }
}