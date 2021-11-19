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

import android.content.Context;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.RadioLibrary;

// Upper class for fragments of the main activity
public abstract class MainActivityFragment extends Fragment {
  protected static final int DEFAULT_RESOURCE = -1;
  protected static Bitmap DEFAULT_ICON = null;
  private static MainActivity MAIN_ACTIVITY = null;
  private View view;
  private boolean isCreationDone = false;

  // Required empty constructor
  public MainActivityFragment() {
    super();
  }

  public static void onActivityCreated(@NonNull MainActivity mainActivity) {
    MainActivityFragment.MAIN_ACTIVITY = mainActivity;
    // Fetch needed static values
    createDefaultIcon();
  }

  private static void createDefaultIcon() {
    Drawable drawable = ContextCompat.getDrawable(MAIN_ACTIVITY, R.drawable.ic_radio_white_24dp);
    // Deep copy
    assert drawable != null;
    Drawable.ConstantState constantState = drawable.mutate().getConstantState();
    assert constantState != null;
    drawable = constantState.newDrawable();
    Canvas canvas = new Canvas();
    DEFAULT_ICON = Bitmap.createBitmap(
      drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
    canvas.setBitmap(DEFAULT_ICON);
    drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    drawable.draw(canvas);
  }

  protected static RadioLibrary getRadioLibrary() {
    return MAIN_ACTIVITY.getRadioLibrary();
  }

  protected static void tell(int message) {
    MAIN_ACTIVITY.tell(message);
  }

  protected static void tell(@NonNull String message) {
    MAIN_ACTIVITY.tell(message);
  }

  @Nullable
  protected static MainActivity getMainActivity() {
    return MAIN_ACTIVITY;
  }

  @Nullable
  @Override
  public Context getContext() {
    Context context = super.getContext();
    return (context == null) ? MAIN_ACTIVITY : context;
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

  public void onCreateOptionsMenu(@NonNull Menu menu) {
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