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
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

public class RadiosModifyAdapter extends RadiosDisplayAdapter<RadiosModifyAdapter.ViewHolder> {
  @NonNull
  private final NestedScrollView nestedScrollView;

  public RadiosModifyAdapter(
    @NonNull RecyclerView recyclerView,
    @NonNull Listener listener,
    @NonNull NestedScrollView nestedScrollView) {
    super(Radios::getInstance, R.layout.row_modify_radio, recyclerView, listener);
    this.nestedScrollView = nestedScrollView;
    // RecyclerView shall be defined for Adapter
    new ItemTouchHelper(new RadioItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
  }

  @NonNull
  public static DisplayMetrics getDisplayMetrics(@NonNull Context context) {
    final DisplayManager displayManager =
      (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    final Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
    return context.createDisplayContext(defaultDisplay).getResources().getDisplayMetrics();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(getView(parent));
  }

  private class RadioItemTouchHelperCallback extends ItemTouchHelper.Callback {
    private static final int DRAG_FLAGS = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
    private static final int IDLE_FLAGS = ItemTouchHelper.START | ItemTouchHelper.END;

    @Override
    public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      return makeMovementFlags(DRAG_FLAGS, IDLE_FLAGS);
    }

    @Override
    public boolean onMove(
      @NonNull RecyclerView recyclerView,
      @NonNull RecyclerView.ViewHolder viewHolder,
      @NonNull RecyclerView.ViewHolder targetViewHolder) {
      ((MainActivity) recyclerView.getContext()).setToolbarExpanded(false);
      return ((Radios) radios).swap(viewHolder.getAbsoluteAdapterPosition(), targetViewHolder.getAbsoluteAdapterPosition());
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
      ((Radios) radios).remove(viewHolder.getAbsoluteAdapterPosition());
    }

    @Override
    public void onChildDraw(
      @NonNull Canvas c,
      @NonNull RecyclerView recyclerView,
      @NonNull RecyclerView.ViewHolder viewHolder,
      float dX,
      float dY,
      int actionState,
      boolean isCurrentlyActive) {
      super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
      // Scroll up or down.
      // Caution: algorithm is dedicated to this specific layout.
      if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
        final int screenHeight = getDisplayMetrics(recyclerView.getContext()).heightPixels;
        final View itemView = viewHolder.itemView;
        final int[] location = new int[2];
        itemView.getLocationOnScreen(location);
        final int itemTop = itemView.getTop();
        final int itemAbsoluteLocation = location[1];
        final int threshold = screenHeight / 6;
        final int dy = threshold / 10;
        if ((Integer.min(itemTop, itemAbsoluteLocation) < threshold) && (dY < 0)) {
          nestedScrollView.smoothScrollBy(0, -dy);
        } else if ((itemAbsoluteLocation > screenHeight - threshold) && (dY > 0)) {
          nestedScrollView.smoothScrollBy(0, dy);
        }
      }
    }
  }

  public class ViewHolder extends RadiosDisplayAdapter<?>.ViewHolder {
    @NonNull
    private final ImageButton preferredImageButton;

    protected ViewHolder(@NonNull View itemView) {
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