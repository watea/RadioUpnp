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
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import java.util.List;
import java.util.Objects;
import java.util.Vector;

public class RadiosModifyAdapter extends RecyclerView.Adapter<RadiosModifyAdapter.ViewHolder> {
  private static final String LOG_TAG = RadiosModifyAdapter.class.getName();
  @NonNull
  private final Context context;
  @NonNull
  private final Listener listener;
  private final int iconSize;
  @NonNull
  private final RadioLibrary radioLibrary;
  @NonNull
  private final List<Long> radioIds = new Vector<>();

  public RadiosModifyAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull RadioLibrary radioLibrary,
    int iconSize) {
    this.context = context;
    this.listener = listener;
    this.radioLibrary = radioLibrary;
    this.iconSize = iconSize;
  }

  // Shall be called
  public void attachToRecyclerView(@NonNull RecyclerView recyclerView) {
    new ItemTouchHelper(new RadioItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
  }

  // Shall be called on view resume (only)
  @SuppressLint("NotifyDataSetChanged")
  public void onRefresh() {
    radioIds.clear();
    radioIds.addAll(radioLibrary.getAllRadioIds());
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
    viewHolder.setView(radioIds.isEmpty() ?
      Radio.DUMMY_RADIO : Objects.requireNonNull(radioLibrary.getFrom(radioIds.get(position))));
  }

  @Override
  public int getItemCount() {
    return radioIds.isEmpty() ? 1 : radioIds.size();
  }

  private void databaseWarn() {
    Log.w(LOG_TAG, "Internal failure, radio database update failed");
  }

  public interface Listener {
    void onModifyClick(@NonNull Radio radio);
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
      if (radioLibrary.move(fromId, toId)) {
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
      return !radioIds.isEmpty();
    }

    @Override
    public boolean isItemViewSwipeEnabled() {
      return !radioIds.isEmpty();
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
      int position = viewHolder.getAbsoluteAdapterPosition();
      if (radioLibrary.deleteFrom(radioIds.get(position)) > 0) {
        // Database updated, update view
        radioIds.remove(position);
        notifyItemRemoved(position);
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
      itemView.findViewById(R.id.row_modify_radio_name_text_view).setOnClickListener(
        v -> listener.onModifyClick(radio));
      // Preferred action
      preferredImageButton.setOnClickListener(v -> {
        if (!radioLibrary.setPreferred(radio.getId(), !radio.isPreferred())) {
          databaseWarn();
        }
      });
      // Listener to detect Preferred change as it may be modified externally
      radioLibrary.addListener((radioId, isPreferred) -> {
        if (radio.getId().equals(radioId)) {
          radio.togglePreferred();
          setPreferredButton();
        }
      });
    }

    private void setView(@NonNull Radio radio) {
      this.radio = radio;
      if (this.radio == Radio.DUMMY_RADIO) {
        decorate(
          Objects.requireNonNull(
            ContextCompat.getDrawable(context, R.drawable.ic_error_gray_24dp)),
          context.getString(R.string.radio_no_radio));
      } else {
        decorate(
          new BitmapDrawable(
            context.getResources(),
            Bitmap.createScaledBitmap(this.radio.getIcon(), iconSize, iconSize, false)),
          this.radio.getName());
      }
      setPreferredButton();
    }

    private void decorate(
      @NonNull Drawable drawable,
      @NonNull String text) {
      radioNameTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
        drawable, null, null, null);
      radioNameTextView.setText(text);
      setPreferredButton();
    }

    private void setPreferredButton() {
      preferredImageButton.setImageResource(radio.isPreferred() ?
        R.drawable.ic_star_white_30dp : R.drawable.ic_star_border_white_30dp);
      preferredImageButton.setVisibility(getButtonVisibility());
    }

    private int getButtonVisibility() {
      return (radio == Radio.DUMMY_RADIO) ? View.INVISIBLE : View.VISIBLE;
    }
  }
}