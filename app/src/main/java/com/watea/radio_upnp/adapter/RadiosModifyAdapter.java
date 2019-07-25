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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.util.List;
import java.util.Vector;

public class RadiosModifyAdapter extends RecyclerView.Adapter<RadiosModifyAdapter.ViewHolder> {
  @NonNull
  private final Context mContext;
  private final int mIconSize;
  @NonNull
  private final Listener mListener;
  @NonNull
  private List<Long> mRadioIds = new Vector<>(); // Dummy default

  public RadiosModifyAdapter(
    @NonNull Context context, @NonNull Listener listener, @NonNull RecyclerView recyclerView) {
    mContext = context;
    mListener = listener;
    new ItemTouchHelper(new RadioItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
    Configuration configuration = mContext.getResources().getConfiguration();
    // Image size same order as screen size to get reasonable layout
    mIconSize = ((configuration.orientation == Configuration.ORIENTATION_PORTRAIT) ?
      configuration.screenWidthDp : configuration.screenHeightDp) / 2;
  }

  // Content setter, must be called
  public void setRadioIds(@NonNull List<Long> radioIds) {
    mRadioIds = radioIds;
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
    //noinspection ConstantConditions
    viewHolder.setView(mListener.getRadioFromId(mRadioIds.get(position)));
  }

  @Override
  public int getItemCount() {
    return mRadioIds.size();
  }

  public interface Listener {
    @Nullable
    Radio getRadioFromId(@NonNull Long radioId);

    void onModifyClick(@NonNull Long radioId);

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
      int from = viewHolder.getAdapterPosition();
      Long fromId = mRadioIds.get(from);
      int to = targetViewHolder.getAdapterPosition();
      Long toId = mRadioIds.get(to);
      if (mListener.onMove(fromId, toId)) {
        // Database updated, update view
        mRadioIds.set(to, fromId);
        mRadioIds.set(from, toId);
        notifyItemMoved(from, to);
        return true;
      } else {
        return false;
      }
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
      int position = viewHolder.getAdapterPosition();
      if (mListener.onDelete(mRadioIds.get(position))) {
        // Database updated, update view
        mRadioIds.remove(position);
        notifyItemRemoved(position);
      }
    }
  }

  class ViewHolder extends RecyclerView.ViewHolder {
    @NonNull
    private final TextView mRadioNameTextView;
    private Radio mRadio;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      mRadioNameTextView = itemView.findViewById(R.id.row_modify_radio_name);
      // Edit action
      itemView.findViewById(R.id.row_modify_radio_name).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            mListener.onModifyClick(mRadio.getId());
          }
        });
    }

    private void setView(@NonNull Radio radio) {
      mRadio = radio;
      mRadioNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        new BitmapDrawable(
          mContext.getResources(),
          Bitmap.createScaledBitmap(mRadio.getIcon(), mIconSize, mIconSize, false)),
        null,
        null,
        null);
      mRadioNameTextView.setText(mRadio.getName());
    }
  }
}