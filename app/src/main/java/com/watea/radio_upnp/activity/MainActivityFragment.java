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

package com.watea.radio_upnp.activity;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

// Upper class for fragments of the main activity
public abstract class MainActivityFragment extends Fragment {
  protected static final int DEFAULT_RESOURCE = -1;
  protected static int ERROR_COLOR;
  protected static int SELECTED_COLOR;
  protected static Drawable CAST_ICON = null;
  protected static Bitmap DEFAULT_ICON = null;
  protected static MainActivity MAIN_ACTIVITY = null;
  private View view;
  private boolean isCreationDone = false;

  // Required empty constructor
  public MainActivityFragment() {
    super();
  }

  public static void onActivityCreated(@NonNull MainActivity mainActivity) {
    MAIN_ACTIVITY = mainActivity;
    // Fetch needed static values
    ERROR_COLOR = ContextCompat.getColor(MAIN_ACTIVITY, R.color.darkRed);
    SELECTED_COLOR = MaterialColors.getColor(
      MAIN_ACTIVITY,
      R.attr.colorPrimary,
      ContextCompat.getColor(MAIN_ACTIVITY, R.color.lightBlue));
    // Static definition of cast icon color (may change with theme)
    CAST_ICON = AppCompatResources.getDrawable(MAIN_ACTIVITY, R.drawable.ic_cast_white_24dp);
    assert CAST_ICON != null;
    CAST_ICON.setTint(SELECTED_COLOR);
    createDefaultIcon();
  }

  private static void createDefaultIcon() {
    Drawable drawable = ContextCompat.getDrawable(MAIN_ACTIVITY, R.drawable.ic_radio_white_24dp);
    // Deep copy
    assert drawable != null;
    Drawable.ConstantState constantState = drawable.mutate().getConstantState();
    assert constantState != null;
    drawable = constantState.newDrawable();
    drawable.setTint(
      MAIN_ACTIVITY.getResources().getColor(R.color.darkGray, MAIN_ACTIVITY.getTheme()));
    Canvas canvas = new Canvas();
    DEFAULT_ICON = Bitmap.createBitmap(
      drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
    canvas.setBitmap(DEFAULT_ICON);
    drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    drawable.draw(canvas);
  }

  public static RadioLibrary getRadioLibrary() {
    return MAIN_ACTIVITY.getRadioLibrary();
  }

  protected static void tell(int message) {
    MAIN_ACTIVITY.tell(message);
  }

  protected static void tell(@NonNull String message) {
    MAIN_ACTIVITY.tell(message);
  }

  protected static boolean upnpSearch() {
    return MAIN_ACTIVITY.upnpSearch();
  }

  protected static boolean upnpReset() {
    return MAIN_ACTIVITY.upnpReset();
  }

  // radio is null for current
  protected static void startReading(@Nullable Radio radio) {
    MAIN_ACTIVITY.startReading(radio);
  }

  @SuppressWarnings("SameParameterValue")
  @NonNull
  protected static Fragment setFragment(@NonNull Class<? extends Fragment> fragment) {
    return MAIN_ACTIVITY.setFragment(fragment);
  }

  public void onCreateOptionsMenu(@NonNull Menu menu) {
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    if (!isCreationDone) {
      view = onCreateViewFiltered(inflater, container);
    }
    return view;
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    if (!isCreationDone) {
      onActivityCreatedFiltered(savedInstanceState);
      isCreationDone = true;
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    // Decorate
    MAIN_ACTIVITY.onFragmentResume(this);
  }

  @NonNull
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> {
    };
  }

  @NonNull
  public View.OnLongClickListener getFloatingActionButtonOnLongClickListener() {
    return v -> false;
  }

  public int getFloatingActionButtonResource() {
    return DEFAULT_RESOURCE;
  }

  public int getMenuId() {
    return DEFAULT_RESOURCE;
  }

  public abstract int getTitle();

  protected abstract void onActivityCreatedFiltered(@Nullable Bundle savedInstanceState);

  @Nullable
  protected abstract View onCreateViewFiltered(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container);

  protected boolean isActuallyAdded() {
    return ((getActivity() != null) && isAdded());
  }
}