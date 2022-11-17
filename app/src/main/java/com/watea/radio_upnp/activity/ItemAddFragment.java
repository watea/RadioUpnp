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

import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import java.net.MalformedURLException;

public class ItemAddFragment extends ItemFragment {
  private static final String LOG_TAG = ItemAddFragment.class.getName();

  @Override
  public int getTitle() {
    return R.string.title_item_add;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (!super.onOptionsItemSelected(item)) {
      boolean result = (urlWatcher.url == null);
      try {
        assert getRadioLibrary() != null;
        result = !result && getRadioLibrary().add(
          new Radio(getRadioName(), urlWatcher.url, webPageWatcher.url, false, getIcon()));
      } catch (Exception exception) {
        Log.e(LOG_TAG, "onOptionsItemSelected: internal failure", exception);
      }
      if (!result) {
        tell(R.string.radio_database_update_failed);
      }
      onBackPressed();
    }
    // Always true
    return true;
  }
}