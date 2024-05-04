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
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

public class RadiosModifyAdapter extends RadiosDisplayAdapter<RadiosModifyAdapter.ViewHolder> {
  public RadiosModifyAdapter(@NonNull RecyclerView recyclerView, @NonNull Listener listener) {
    super(MainActivity::getRadios, R.layout.row_modify_radio, recyclerView, listener);
    // RecyclerView shall be defined for Adapter
    new ItemTouchHelper(new RadioItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
    return new ViewHolder(getView(viewGroup));
  }

  public interface Listener extends RadiosDisplayAdapter.Listener {
    void onWarnChange();
  }

  private class RadioItemTouchHelperCallback extends ItemTouchHelper.Callback {
    private static final int DRAG_FLAGS = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
    private static final int IDLE_FLAGS = ItemTouchHelper.START | ItemTouchHelper.END;

    @Override
    public int getMovementFlags(
      @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      final boolean isCurrentRadio = ((ViewHolder) viewHolder).isCurrentRadio();
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
      return ((Radios) radios).swap(
        viewHolder.getAbsoluteAdapterPosition(), targetViewHolder.getAbsoluteAdapterPosition());
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
      ((Radios) radios).remove(viewHolder.getAbsoluteAdapterPosition());
    }
  }

  public class ViewHolder extends RadiosDisplayAdapter<?>.ViewHolder {
    @NonNull
    private final ImageButton preferredImageButton;

    ViewHolder(@NonNull View itemView) {
      super(itemView, R.id.row_modify_radio_text_view);
      (preferredImageButton = itemView.findViewById(R.id.row_radio_preferred_image_button))
        .setOnClickListener(v -> ((Radios) radios).setPreferred(radio, !radio.isPreferred()));
    }

    @Override
    protected void setView(@NonNull Radio radio) {
      super.setView(radio);
      preferredImageButton.setImageResource(this.radio.isPreferred() ?
        R.drawable.ic_star_white_30dp : R.drawable.ic_star_border_white_30dp);
    }
  }
}