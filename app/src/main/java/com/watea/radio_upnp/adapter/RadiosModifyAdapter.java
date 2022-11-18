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
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

public class RadiosModifyAdapter extends RadiosAdapter<RadiosModifyAdapter.ViewHolder> {
  private static final String LOG_TAG = RadiosModifyAdapter.class.getName();
  @NonNull
  private final RadioLibrary.Listener modifyRadioLibraryListener = new RadioLibrary.Listener() {
    @Override
    public void onPreferredChange(@NonNull Radio radio) {
      notifyItemChanged(getIndexOf(radio));
    }

    @Override
    public void onRemove(@NonNull Long radioId) {
      final int index = getIndexOf(radioId);
      radioIds.remove(index);
      notifyItemRemoved(index);
      onCountChange(radioIds.isEmpty());
    }

    @Override
    public void onMove(@NonNull Long from, @NonNull Long to) {
      final int fromIndex = getIndexOf(from);
      final int toIndex = getIndexOf(to);
      radioIds.set(toIndex, from);
      radioIds.set(fromIndex, to);
      notifyItemMoved(fromIndex, toIndex);
    }
  };

  public RadiosModifyAdapter(
    @NonNull Listener listener,
    int iconSize,
    @NonNull RecyclerView recyclerView) {
    super(listener, iconSize);
    // RecyclerView shall be defined for Adapter
    new ItemTouchHelper(new RadioItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
    // Adapter shall be defined for RecyclerView
    recyclerView.setAdapter(this);
  }

  // Must be called
  public void set(@NonNull RadioLibrary radioLibrary) {
    super.set(radioLibrary, modifyRadioLibraryListener, false);
  }

  private void databaseWarn() {
    Log.w(LOG_TAG, "Internal failure, radio database update failed");
  }

  public interface Listener extends RadiosAdapter.Listener {
    void onWarnChange();
  }

  private class RadioItemTouchHelperCallback extends ItemTouchHelper.Callback {
    private static final int DRAG_FLAGS = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
    private static final int IDLE_FLAGS = ItemTouchHelper.START | ItemTouchHelper.END;

    @Override
    public int getMovementFlags(
      @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      assert radioLibrary != null;
      final boolean isCurrentRadio = radioLibrary.isCurrentRadio(((ViewHolder) viewHolder).radio);
      if (isCurrentRadio) {
        ((Listener) listener).onWarnChange();
      }
      return makeMovementFlags(DRAG_FLAGS, isCurrentRadio ? 0 : IDLE_FLAGS);
    }

    @Override
    public boolean onMove(
      @NonNull RecyclerView recyclerView,
      @NonNull RecyclerView.ViewHolder viewHolder,
      @NonNull RecyclerView.ViewHolder targetViewHolder) {
      final Long fromId = radioIds.get(viewHolder.getAbsoluteAdapterPosition());
      final Long toId = radioIds.get(targetViewHolder.getAbsoluteAdapterPosition());
      assert radioLibrary != null;
      final boolean result = radioLibrary.move(fromId, toId);
      if (!result) {
        databaseWarn();
      }
      return result;
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
      assert radioLibrary != null;
      if (!radioLibrary.deleteFrom(radioIds.get(position))) {
        databaseWarn();
      }
    }
  }

  protected class ViewHolder extends RadiosAdapter<RadiosModifyAdapter.ViewHolder>.ViewHolder {
    @NonNull
    private final ImageButton preferredImageButton;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      preferredImageButton = itemView.findViewById(R.id.row_radio_preferred_image_button);
      preferredImageButton.setOnClickListener(v -> {
        assert radioLibrary != null;
        if (!radioLibrary.setPreferred(radio.getId(), !radio.isPreferred())) {
          databaseWarn();
        }
      });
    }

    @NonNull
    @Override
    protected TextView getRadioTextView(@NonNull View itemView) {
      return itemView.findViewById(R.id.row_modify_radio_text_view);
    }

    @Override
    protected void setImage(@NonNull BitmapDrawable bitmapDrawable) {
      radioTextView
        .setCompoundDrawablesRelativeWithIntrinsicBounds(bitmapDrawable, null, null, null);
    }

    @Override
    protected void setView(@NonNull Radio radio) {
      super.setView(radio);
      preferredImageButton.setImageResource(radio.isPreferred() ?
        R.drawable.ic_star_white_30dp : R.drawable.ic_star_border_white_30dp);
    }
  }
}