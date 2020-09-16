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

import android.os.Bundle;
import android.view.Menu;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.watea.radio_upnp.model.RadioLibrary;

import java.util.Objects;

// Upper class for fragments of the main activity
public abstract class MainActivityFragment extends Fragment {
  protected static final int RADIO_ICON_SIZE = 300;
  protected static final int DEFAULT_RESOURCE = -1;
  protected RadioLibrary radioLibrary = null;
  protected Provider provider = null;

  // Required empty constructor
  public MainActivityFragment() {
    super();
  }

  public void onCreateOptionsMenu(@NonNull Menu menu) {
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    provider = (Provider) getActivity();
    radioLibrary = Objects.requireNonNull(provider).getRadioLibrary();
  }

  @Override
  public void onResume() {
    super.onResume();
    // Decorate
    provider.onFragmentResume(this);
  }

  @NonNull
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
      }
    };
  }

  @NonNull
  public View.OnLongClickListener getFloatingActionButtonOnLongClickListener() {
    return new View.OnLongClickListener() {
      @Override
      public boolean onLongClick(View view) {
        return false;
      }
    };
  }

  public int getFloatingActionButtonResource() {
    return DEFAULT_RESOURCE;
  }

  public int getMenuId() {
    return DEFAULT_RESOURCE;
  }

  public abstract int getTitle();

  protected boolean isActuallyAdded() {
    return ((getActivity() != null) && isAdded());
  }

  protected void tell(int message) {
    Snackbar.make(Objects.requireNonNull(getView()), message, Snackbar.LENGTH_LONG).show();
  }

  protected void tell(@NonNull String message) {
    Snackbar.make(Objects.requireNonNull(getView()), message, Snackbar.LENGTH_LONG).show();
  }

  public interface Provider {
    @NonNull
    RadioLibrary getRadioLibrary();

    void onFragmentResume(@NonNull MainActivityFragment mainActivityFragment);

    @NonNull
    Fragment setFragment(@NonNull Class<? extends Fragment> fragment);
  }
}