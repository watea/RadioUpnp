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
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.NetworkTester;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ADD or MODIFY modes
// mRadio = null for ADD
public class ItemModifyFragment
  extends MainActivityFragment<ItemModifyFragment.Callback>
  implements View.OnClickListener {
  private static final String LOG_TAG = ItemModifyFragment.class.getSimpleName();
  private final Handler mHandler = new Handler();
  // <HMI assets
  private View mView;
  private EditText mNameEditText;
  private EditText mUrlEditText;
  private EditText mWebPageEditText;
  private UrlWatcher mUrlWatcher;
  private UrlWatcher mWebPageWatcher;
  // />
  // Default values; ADD mode
  private Radio mRadio = null;
  private String mRadioName = null;
  private String mRadioUrl = null;
  private String mRadioWebPage = null;
  private Bitmap mRadioIcon = null;
  private boolean mIsRadioIconNew = true;

  @Override
  public void onClick(View view) {
    if (NetworkTester.isDeviceOffline(getActivity())) {
      Snackbar.make(mView, R.string.no_internet, Snackbar.LENGTH_LONG).show();
    } else {
      new RadioMetaDataSearch().start();
    }
  }

  // MainActivityFragment
  @Override
  public void onCreateOptionsMenu(@NonNull MenuInflater menuInflater, @NonNull Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_item_modify, menu);
  }

  @Override
  public void onResume() {
    super.onResume();
    // Decorate
    mCallback.onResume((mRadio == null), this, R.drawable.ic_image_black_24dp);
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Restore saved state, if any
    if (savedInstanceState != null) {
      // "set" values...
      mRadio = mRadioLibrary.getFrom(savedInstanceState.getLong(getString(R.string.key_radio_id)));
      // ...others
      mRadioName = savedInstanceState.getString(getString(R.string.key_radio_name));
      mRadioUrl = savedInstanceState.getString(getString(R.string.key_radio_url));
      mRadioWebPage = savedInstanceState.getString(getString(R.string.key_radio_web_page));
      mRadioIcon = BitmapFactory.decodeFile(
        savedInstanceState.getString(getString(R.string.key_radio_icon_file)));
      mIsRadioIconNew = savedInstanceState.getBoolean(getString(R.string.key_is_radio_icon_new));
    }
  }

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    mView = inflater.inflate(R.layout.content_item_modify, container, false);
    mNameEditText = mView.findViewById(R.id.name_edit_text);
    mUrlEditText = mView.findViewById(R.id.url_edit_text);
    mUrlWatcher = new UrlWatcher(mUrlEditText);
    mUrlEditText.addTextChangedListener(mUrlWatcher);
    mWebPageEditText = mView.findViewById(R.id.web_page_edit_text);
    mWebPageWatcher = new UrlWatcher(mWebPageEditText);
    mWebPageEditText.addTextChangedListener(mWebPageWatcher);
    // Fill content
    mNameEditText.setText(mRadioName);
    if (mRadioIcon == null) {
      mRadioIcon = BitmapFactory.decodeResource(getActivity().getResources(), R.drawable.ic_radio);
    }
    setNameEditTextIcon();
    mUrlEditText.setText(mRadioUrl);
    mWebPageEditText.setText(mRadioWebPage);
    return mView;
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    // "set" values...
    outState.putLong(getString(R.string.key_radio_id), (mRadio == null) ? -1 : mRadio.getId());
    // ...others
    outState.putString(getString(R.string.key_radio_name), mNameEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_url), mUrlEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_web_page), mWebPageEditText.getText().toString());
    outState.putBoolean(getString(R.string.key_is_radio_icon_new), mIsRadioIconNew);
    outState.putString(getString(R.string.key_radio_icon_file),
      mRadioLibrary.bitmapToFile(mRadioIcon, Integer.toString(hashCode())).getPath());
  }

  @Override
  public void onPause() {
    flushKeyboard(mView);
    super.onPause();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    flushKeyboard(mView);
    switch (item.getItemId()) {
      case R.id.action_test:
        if (NetworkTester.isDeviceOffline(getActivity())) {
          Snackbar.make(mView, R.string.no_internet, Snackbar.LENGTH_LONG).show();
        } else {
          if (mUrlWatcher.isNull()) {
            Snackbar.make(mView, R.string.connection_test_aborted, Snackbar.LENGTH_LONG).show();
          } else {
            Snackbar.make(mView, R.string.connection_test_in_progress, Snackbar.LENGTH_LONG).show();
            new TestRadioURL().start();
          }
        }
        break;
      case R.id.action_done:
        if (mUrlWatcher.isNull()) {
          Snackbar.make(mView, R.string.radio_definition_error, Snackbar.LENGTH_LONG).show();
        } else {
          if (mRadio == null) {
            //noinspection ConstantConditions
            mRadio = new Radio(
              mNameEditText.getText().toString(),
              null, // File name not known yet
              Radio.Type.MISC,
              Radio.Language.OTHER,
              mUrlWatcher.mUrl,
              mWebPageWatcher.mUrl,
              Radio.Quality.MEDIUM);
            if (mRadioLibrary.insertAndSaveIcon(mRadio, mRadioIcon) <= 0) {
              Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, adding in database");
            }
          } else {
            mRadio.setName(mNameEditText.getText().toString());
            if (mIsRadioIconNew) {
              // Same file name reused to store icon
              mRadioLibrary.bitmapToFile(
                mRadioIcon,
                mRadio.getIconFile().getName().replace(".png", ""));
            }
            mRadio.setURL(mUrlWatcher.mUrl);
            mRadio.setWebPageURL(mWebPageWatcher.mUrl);
            if (mRadioLibrary.updateFrom(mRadio.getId(), mRadio.toContentValues()) <= 0) {
              Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, updating database");
            }
          }
          // Back to previous fragment
          getFragmentManager().popBackStack();
        }
        break;
      default:
        // If we got here, the user's action was not recognized
        // Invoke the superclass to handle it
        return super.onOptionsItemSelected(item);
    }
    return true;
  }

  // Must be called before MODIFY mode
  @NonNull
  public ItemModifyFragment set(@NonNull Radio radio) {
    mRadio = radio;
    mIsRadioIconNew = false;
    mRadioName = mRadio.getName();
    mRadioUrl = mRadio.getURL().toString();
    URL webPageURL = mRadio.getWebPageURL();
    mRadioWebPage = (webPageURL == null) ? null : webPageURL.toString();
    mRadioIcon = mRadio.getIcon();
    return this;
  }

  private void setNameEditTextIcon() {
    int screenWidthDp = getResources().getConfiguration().screenWidthDp;
    // Image size same order as screen size to get reasonable layout
    //noinspection SuspiciousNameCombination
    mNameEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
      null,
      new BitmapDrawable(
        getResources(), Bitmap.createScaledBitmap(mRadioIcon, screenWidthDp, screenWidthDp, false)),
      null,
      null);
  }

  private void flushKeyboard(@NonNull View view) {
    InputMethodManager inputMethodManager =
      (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (inputMethodManager != null) {
      inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
  }

  public interface Callback {
    void onResume(
      boolean isAddMode,
      @NonNull View.OnClickListener floatingActionButtonOnClickListener,
      int floatingActionButtonResource);
  }

  private class RadioMetaDataSearch extends Thread {
    // Search for icon
    final Pattern pattern = Pattern.compile(".*(http.*\\.(png|jpg)).*");

    @Override
    public void run() {
      // Search radio web site
      Bitmap tryIcon = null;
      try {
        Element head = Jsoup.connect(mWebPageEditText.getText().toString()).get().head();
        // Parse site data
        if (head != null) {
          Matcher matcher = pattern.matcher(head.toString());
          if (matcher.find()) {
            tryIcon = NetworkTester.getBitmapFromUrl(new URL(matcher.group(1)));
          }
        }
      } catch (Exception exception) {
        Log.i(LOG_TAG, "Error performing radio site search");
      }
      final Bitmap icon = tryIcon;
      // Modify HMI
      mHandler.post(new Runnable() {
        @Override
        public void run() {
          if (isActuallyShown(mView)) {
            if (icon == null) {
              Snackbar.make(mView, R.string.no_icon_found, Snackbar.LENGTH_LONG).show();
            } else {
              // Resize bitmap as a square
              int height = icon.getHeight();
              int width = icon.getWidth();
              int min = Math.min(height, width);
              mRadioIcon = Bitmap.createBitmap(
                icon,
                (width - min) / 2,
                (height - min) / 2,
                min,
                min,
                null,
                false);
              mIsRadioIconNew = true;
              setNameEditTextIcon();
              Snackbar.make(mView, R.string.radio_icon_updated, Snackbar.LENGTH_LONG).show();
            }
          }
        }
      });
    }
  }

  private class TestRadioURL extends Thread {
    @Override
    public void run() {
      String contentType = null;
      HttpURLConnection httpURLConnection = null;
      try {
        httpURLConnection = NetworkTester.getActualHttpURLConnection(mUrlWatcher.mUrl);
        // Actual connection test: header must be audio/mpeg
        contentType = httpURLConnection.getHeaderField("Content-Type");
        // If we get there, connection has occurred
        Log.d(LOG_TAG, "Connection test status: " + httpURLConnection.getResponseCode());
      } catch (IOException iOException) {
        // Fires also in case of timeout
        Log.i(LOG_TAG, "Radio URL IO exception");
      } finally {
        if (httpURLConnection != null) {
          httpURLConnection.disconnect();
        }
      }
      final boolean isTestOK = (contentType != null) && contentType.equals("audio/mpeg");
      mHandler.post(new Runnable() {
        @Override
        public void run() {
          if (isActuallyShown(mView)) {
            String msg = getResources().getString(isTestOK ?
              R.string.connection_test_successful :
              R.string.connection_test_failed) +
              ": " + mUrlWatcher.mUrl.toString();
            Snackbar.make(mView, msg, Snackbar.LENGTH_LONG).show();
          }
        }
      });
    }
  }

  // Utility class to listen for URL edition
  private class UrlWatcher implements TextWatcher {
    private final int mDefaultColor;
    private final EditText mEditText;
    private URL mUrl;

    // editText: EditText to watch
    // mayBeVoid: is empty field allowed?
    private UrlWatcher(EditText editText) {
      mEditText = editText;
      mDefaultColor = mEditText.getCurrentTextColor();
      mUrl = null;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int ount, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
      try {
        mUrl = new URL(s.toString());
        mEditText.setTextColor(mDefaultColor);
      } catch (MalformedURLException malformedURLException) {
        if (!isNull()) {
          Snackbar.make(mView, R.string.malformed_url_error, Snackbar.LENGTH_LONG).show();
        }
        mUrl = null;
        mEditText.setTextColor(ContextCompat.getColor(getActivity(), R.color.colorError));
      }
    }

    @SuppressWarnings("NullableProblems")
    @Override
    @Nullable
    public String toString() {
      return isNull() ? null : mUrl.toString();
    }

    private boolean isNull() {
      return (mUrl == null);
    }
  }
}