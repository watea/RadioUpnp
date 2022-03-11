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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import java.util.List;
import java.util.Vector;

public class RadiosModifyAdapter
  extends RecyclerView.Adapter<RadiosModifyAdapter.ViewHolder>
  implements RadioLibrary.Listener {
  private static final String LOG_TAG = RadiosModifyAdapter.class.getName();
  @NonNull
  private final Context context;
  @NonNull
  private final Listener listener;
  private final int iconSize;
  private final List<Long> radioIds = new Vector<>();

  public RadiosModifyAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    int iconSize,
    @NonNull RecyclerView recyclerView) {
    this.context = context;
    this.listener = listener;
    this.iconSize = iconSize;
    // RecyclerView shall be defined for Adapter
    new ItemTouchHelper(new RadioItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
    // Adapter shall be defined for RecyclerView
    recyclerView.setAdapter(this);
  }

  public void onResume() {
    onRefresh();
    getRadioLibrary().addListener(this);
  }

  public void onPause() {
    getRadioLibrary().removeListener(this);
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(
      LayoutInflater.from(parent.getContext()).inflate(R.layout.row_modify_radio, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
    if (getRadioLibrary().isOpen()) {
      Radio radio = getRadioLibrary().getFrom(radioIds.get(position));
      assert radio != null;
      viewHolder.setView(radio);
    }
  }

  @Override
  public int getItemCount() {
    return radioIds.size();
  }

  @Override
  public void onPreferredChange(@NonNull Radio radio) {
    notifyItemChanged(radioIds.indexOf(radio.getId()));
  }

  @SuppressLint("NotifyDataSetChanged")
  @Override
  public void onRefresh() {
    radioIds.clear();
    radioIds.addAll(getRadioLibrary().getAllRadioIds());
    notifyDataSetChanged();
    notifyEmpty();
  }

  private void databaseWarn() {
    Log.w(LOG_TAG, "Internal failure, radio database update failed");
  }

  private void notifyEmpty() {
    listener.onEmpty(radioIds.isEmpty());
  }

  @NonNull
  private RadioLibrary getRadioLibrary() {
    return listener.getRadioLibraryAccess();
  }

  public interface Listener {
    void onModifyClick(@NonNull Radio radio);

    boolean onCheckChange(@NonNull Radio radio);

    void onEmpty(boolean isEmpty);

    @NonNull
    RadioLibrary getRadioLibraryAccess();
  }

  private class RadioItemTouchHelperCallback extends ItemTouchHelper.Callback {
    private static final int DRAG_FLAGS = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
    private static final int IDLE_FLAGS = ItemTouchHelper.START | ItemTouchHelper.END;

    @Override
    public int getMovementFlags(
      @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      return makeMovementFlags(
        DRAG_FLAGS, listener.onCheckChange(((ViewHolder) viewHolder).radio) ? IDLE_FLAGS : 0);
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
      if (getRadioLibrary().move(fromId, toId)) {
        // Database updated, update view
        radioIds.set(to, fromId);
        radioIds.set(from, toId);
        notifyItemMoved(from, to);
        return true;
      }
      databaseWarn();
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
      final int position = viewHolder.getAbsoluteAdapterPosition();
      if (getRadioLibrary().deleteFrom(radioIds.get(position))) {
        // Database updated, update view
        radioIds.remove(position);
        notifyItemRemoved(position);
        notifyEmpty();
      } else {
        databaseWarn();
      }
    }
  }

  protected class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final TextView radioNameTextView;
    @NonNull
    private final ImageButton preferredImageButton;
    @NonNull
    private Radio radio = Radio.DUMMY_RADIO;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      radioNameTextView = itemView.findViewById(R.id.row_modify_radio_name_text_view);
      preferredImageButton = itemView.findViewById(R.id.row_radio_preferred_image_button);
      // Edit action
      itemView
        .findViewById(R.id.row_modify_radio_name_text_view)
        .setOnClickListener(v -> listener.onModifyClick(radio));
      // Preferred action
      preferredImageButton.setOnClickListener(v -> {
        if (!getRadioLibrary().setPreferred(radio.getId(), !radio.isPreferred())) {
          databaseWarn();
        }
      });
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
      preferredImageButton.setImageResource(radio.isPreferred() ?
        R.drawable.ic_star_white_30dp : R.drawable.ic_star_border_white_30dp);
    }
  }
}