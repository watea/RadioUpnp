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

import android.app.Fragment;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;

import com.watea.radio_upnp.model.RadioLibrary;

public abstract class MainActivityFragment<C> extends Fragment {
  protected RadioLibrary mRadioLibrary = null;
  protected C mCallback = null;

  // Required empty constructor
  public MainActivityFragment() {
    super();
  }

  // Utility to test if view actually exists (not diposed) and is on screen
  protected static boolean isActuallyShown(View view) {
    return ((view != null) && view.isShown());
  }

  public void onCreateOptionsMenu(@NonNull MenuInflater menuInflater, @NonNull Menu menu) {
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mRadioLibrary = getProvider().getRadioLibrary();
  }

  @Override
  public void onResume() {
    super.onResume();
    // Activity may have been re-created, so new library instance is used
    mRadioLibrary = getProvider().getRadioLibrary();
    mCallback = getProvider().getFragmentCallback(this);
  }

  @NonNull
  private Provider<C> getProvider() {
    //noinspection unchecked
    return (Provider<C>) getActivity();
  }

  public interface Provider<C> {
    @NonNull
    RadioLibrary getRadioLibrary();

    @NonNull
    C getFragmentCallback(@NonNull MainActivityFragment<?> mainActivityFragment);
  }
}