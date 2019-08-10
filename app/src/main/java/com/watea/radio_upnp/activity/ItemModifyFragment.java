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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SimpleAdapter;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.NetworkTester;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ADD or MODIFY modes
// mRadio = null for ADD
public class ItemModifyFragment
  extends MainActivityFragment<ItemModifyFragment.Callback>
  implements View.OnClickListener {
  private static final String LOG_TAG = ItemModifyFragment.class.getSimpleName();
  private static final String DAR_FM_API = "http://api.dar.fm/";
  private static final String DAR_FM_PLAYLIST_REQUEST = DAR_FM_API + "playlist.php?q=@callsign%20";
  private static final String DAR_FM_STATIONS_REQUEST = DAR_FM_API + "darstations.php?station_id=";
  private static final String WILDCARD = "*";
  private static final String SPACE_FOR_SEARCH = "%20";
  private static final String DAR_FM_PARTNER_TOKEN = "&partner_token=6453742475";
  private static final String DAR_FM_BASE_URL = "http://stream.dar.fm/";
  private static final String DAR_FM_NAME = "name";
  private static final String DAR_FM_SITE_URL = "site";
  private static final String DAR_FM_IMAGE_URL = "image";
  private static final String DAR_FM_ID = "id";
  private static final Pattern PATTERN = Pattern.compile(".*(http.*\\.(png|jpg)).*");
  private static final Map<String, String> DEFAULT_MESSAGE = new Hashtable<>();
  private final Handler mHandler = new Handler();
  private final List<Map<String, String>> mDarFmRadios = new Vector<>();
  // <HMI assets
  private EditText mNameEditText;
  private EditText mUrlEditText;
  private EditText mWebPageEditText;
  private CheckBox mDarFmCheckbox;
  private UrlWatcher mUrlWatcher;
  private UrlWatcher mWebPageWatcher;
  private AlertDialog mDarFmAlertDialog;
  // />
  // Default values; ADD mode
  private Radio mRadio = null;
  private String mRadioName = null;
  private String mRadioUrl = null;
  private String mRadioWebPage = null;
  private Bitmap mRadioIcon = null;
  private Bitmap mFoundIcon;
  private final Runnable mIconSearchBackground = new Runnable() {
    @Override
    public void run() {
      mFoundIcon = null;
      try {
        Element head = Jsoup.connect(mWebPageEditText.getText().toString()).get().head();
        // Parse site data
        if (head != null) {
          Matcher matcher = PATTERN.matcher(head.toString());
          if (matcher.find()) {
            mFoundIcon = NetworkTester.getBitmapFromUrl(new URL(matcher.group(1)));
          }
        }
      } catch (Exception exception) {
        Log.i(LOG_TAG, "Error performing radio site search");
      }
    }
  };
  private final Runnable mIconSearchForeground = new Runnable() {
    @Override
    public void run() {
      if (mFoundIcon == null) {
        tell(R.string.no_icon_found);
      } else {
        setRadioIcon(mFoundIcon);
        tell(R.string.icon_updated);
      }
    }
  };
  private final Runnable mDarFmSearchBackground = new Runnable() {
    @Override
    public void run() {
      if (mDarFmRadios.get(0) == DEFAULT_MESSAGE) {
        boolean isFound = false;
        try {
          Element stations = Jsoup
            .connect(
              DAR_FM_PLAYLIST_REQUEST + getRadioName().replace(" ", SPACE_FOR_SEARCH) +
                WILDCARD + DAR_FM_PARTNER_TOKEN)
            .get();
          // Parse data
          if (stations != null) {
            for (Element element : stations.getElementsByTag("station")) {
              String id = extractValue(element, "station_id");
              Map<String, String> map = new Hashtable<>();
              map.put(DAR_FM_ID, id);
              Element station = Jsoup
                .connect(DAR_FM_STATIONS_REQUEST + id + DAR_FM_PARTNER_TOKEN)
                .get();
              map.put(DAR_FM_NAME, extractValue(station, "callsign"));
              map.put(DAR_FM_SITE_URL, extractValue(station, "websiteurl"));
              map.put(DAR_FM_IMAGE_URL, extractValue(station, "imageurl"));
              // Remove DEFAULT_MESSAGE
              if (!isFound) {
                mDarFmRadios.clear();
                isFound = true;
              }
              mDarFmRadios.add(map);
            }
          }
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR FM search", iOexception);
        }
        // List void if nothing found
        if (!isFound) {
          mDarFmRadios.clear();
        }
      }
      // When radio is known, fetch icon
      if (mDarFmRadios.size() == 1) {
        mFoundIcon = null;
        try {
          mFoundIcon = NetworkTester.getBitmapFromUrl(
            new URL(mDarFmRadios.get(0).get(DAR_FM_IMAGE_URL)));
        } catch (MalformedURLException malformedURLException) {
          Log.i(LOG_TAG, "Error performing icon search");
        }
      }
    }
  };
  private boolean mIsDarFmSearchRunning;
  private final Runnable mDarFmSearchLast = new Runnable() {
    @Override
    public void run() {
      mIsDarFmSearchRunning = false;
      // Clear if we were disposed
      if (!isActuallyShown() && (mDarFmAlertDialog != null)) {
        mDarFmAlertDialog.dismiss();
      }
    }
  };
  private SimpleAdapter mSimpleAdapter;
  private final Runnable mDarFmSearchForeground = new Runnable() {
    @SuppressLint("SetTextI18n")
    @Override
    public void run() {
      switch (mDarFmRadios.size()) {
        case 0:
          mDarFmAlertDialog.dismiss();
          tell(R.string.dar_fm_failed);
          break;
        case 1:
          Map<String, String> foundRadio = mDarFmRadios.get(0);
          mNameEditText.setText(Objects.requireNonNull(foundRadio.get(DAR_FM_NAME)).toUpperCase());
          mUrlEditText.setText(DAR_FM_BASE_URL + foundRadio.get(DAR_FM_ID));
          mWebPageEditText.setText(foundRadio.get(DAR_FM_SITE_URL));
          mDarFmAlertDialog.dismiss();
          if (mFoundIcon == null) {
            tell(R.string.dar_fm_no_icon_found);
          } else {
            setRadioIcon(mFoundIcon);
            tell(R.string.dar_fm_done);
          }
          break;
        default:
          mSimpleAdapter.notifyDataSetChanged();
      }
    }
  };
  private boolean mIsTestOK;
  private final Runnable mTestRadioURLBackground = new Runnable() {
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
      mIsTestOK = (contentType != null) && contentType.equals("audio/mpeg");
    }
  };
  private final Runnable mTestRadioURLForeground = new Runnable() {
    @Override
    public void run() {
      tell(getResources().getString(
        mIsTestOK ? R.string.connection_test_successful : R.string.connection_test_failed) + ": " +
        mUrlWatcher.mUrl.toString());
    }
  };

  @NonNull
  private static String extractValue(@NonNull Element element, @NonNull String tag) {
    return element.getElementsByTag(tag).first().ownText();
  }

  @Override
  public void onClick(View view) {
    if (NetworkTester.isDeviceOffline(getActivity())) {
      tell(R.string.no_internet);
    } else {
      if (mDarFmCheckbox.isChecked()) {
        if (mIsDarFmSearchRunning) {
          tell(R.string.dar_fm_in_progress);
        } else {
          darFmSearch(DEFAULT_MESSAGE);
        }
      } else {
        new Searcher(mIconSearchBackground, mIconSearchForeground, null, true).start();
      }
    }
  }

  // MainActivityFragment
  @Override
  public void onCreateOptionsMenu(@NonNull MenuInflater menuInflater, @NonNull Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_item_modify, menu);
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
    }
    DEFAULT_MESSAGE.clear();
    DEFAULT_MESSAGE.put(DAR_FM_NAME, getActivity().getResources().getString(R.string.wait_search));
    mIsDarFmSearchRunning = false;
  }

  @Override
  public void onResume() {
    super.onResume();
    // Decorate
    mCallback.onResume((mRadio == null), this);
  }

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    View view = inflater.inflate(R.layout.content_item_modify, container, false);
    mNameEditText = view.findViewById(R.id.name_edit_text);
    mUrlEditText = view.findViewById(R.id.url_edit_text);
    mUrlWatcher = new UrlWatcher(mUrlEditText);
    mUrlEditText.addTextChangedListener(mUrlWatcher);
    mWebPageEditText = view.findViewById(R.id.web_page_edit_text);
    mWebPageWatcher = new UrlWatcher(mWebPageEditText);
    mWebPageEditText.addTextChangedListener(mWebPageWatcher);
    mDarFmCheckbox = view.findViewById(R.id.dar_fm_checkbox);
    // Fill content including recycler
    mNameEditText.setText(mRadioName);
    setRadioIcon((mRadioIcon == null) ?
      BitmapFactory.decodeResource(getActivity().getResources(), R.drawable.ic_radio) : mRadioIcon);
    mUrlEditText.setText(mRadioUrl);
    mWebPageEditText.setText(mRadioWebPage);
    // Order matters!
    mDarFmCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        mCallback.onFABChange(b ? R.drawable.ic_search_black_24dp : R.drawable.ic_image_black_24dp);
        mWebPageEditText.setEnabled(!b);
        mUrlEditText.setEnabled(!b);
      }
    });
    mDarFmCheckbox.setChecked(
      (savedInstanceState == null) ||
        savedInstanceState.getBoolean(getString(R.string.key_dar_fm_checked)));
    // Build alert dialog for DAR FM
    mDarFmAlertDialog = new AlertDialog
      .Builder(getActivity())
      .setAdapter(
        mSimpleAdapter = new SimpleAdapter(
          getActivity(),
          mDarFmRadios,
          R.layout.row_darfm_radio,
          new String[]{DAR_FM_NAME},
          new int[]{R.id.row_darfm_radio_name}),
        new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int i) {
            darFmSearch(mDarFmRadios.get(i));
          }
        })
      .setCancelable(true)
      .create();
    return view;
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
    outState.putString(getString(R.string.key_radio_icon_file),
      mRadioLibrary.bitmapToFile(mRadioIcon, Integer.toString(hashCode())).getPath());
    outState.putBoolean(getString(R.string.key_dar_fm_checked), mDarFmCheckbox.isChecked());
  }

  @Override
  public void onPause() {
    flushKeyboard(Objects.requireNonNull(getView()));
    super.onPause();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    flushKeyboard(Objects.requireNonNull(getView()));
    switch (item.getItemId()) {
      case R.id.action_test:
        if (NetworkTester.isDeviceOffline(getActivity())) {
          tell(R.string.no_internet);
        } else {
          if (mUrlWatcher.isNull()) {
            tell(R.string.connection_test_aborted);
          } else {
            new Searcher(mTestRadioURLBackground, mTestRadioURLForeground, null, true).start();
          }
        }
        break;
      case R.id.action_done:
        if (mUrlWatcher.isNull()) {
          tell(R.string.radio_definition_error);
        } else {
          if (mRadio == null) {
            //noinspection ConstantConditions
            mRadio = new Radio(
              getRadioName(),
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
            mRadio.setName(getRadioName());
            // Same file name reused to store icon
            mRadioLibrary.bitmapToFile(
              mRadioIcon,
              mRadio.getIconFile().getName().replace(".png", ""));
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
    mRadioName = mRadio.getName();
    mRadioUrl = mRadio.getURL().toString();
    URL webPageURL = mRadio.getWebPageURL();
    mRadioWebPage = (webPageURL == null) ? null : webPageURL.toString();
    mRadioIcon = mRadio.getIcon();
    return this;
  }

  private void darFmSearch(@NonNull Map<String, String> radio) {
    mIsDarFmSearchRunning = true;
    mDarFmRadios.clear();
    mDarFmRadios.add(radio);
    if (radio == DEFAULT_MESSAGE) {
      mDarFmAlertDialog.show();
    }
    new Searcher(mDarFmSearchBackground, mDarFmSearchForeground, mDarFmSearchLast, false).start();
  }

  @NonNull
  private String getRadioName() {
    return mNameEditText.getText().toString().toUpperCase();
  }

  private void flushKeyboard(@NonNull View view) {
    InputMethodManager inputMethodManager =
      (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (inputMethodManager != null) {
      inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
  }

  private void setRadioIcon(@NonNull Bitmap icon) {
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
    // Image size same order as screen size to get reasonable layout
    int screenWidthDp = getResources().getConfiguration().screenWidthDp;
    //noinspection SuspiciousNameCombination
    mNameEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
      null,
      new BitmapDrawable(
        getResources(),
        Bitmap.createScaledBitmap(mRadioIcon, screenWidthDp, screenWidthDp, false)),
      null,
      null);
  }

  public interface Callback {
    void onResume(
      boolean isAddMode,
      @NonNull View.OnClickListener floatingActionButtonOnClickListener);

    void onFABChange(int floatingActionButtonResource);
  }

  // Utility to properly handle asynchronous actions
  private class Searcher extends Thread {
    private final Runnable mBackground, mForeground, mLast;

    private Searcher(
      @NonNull Runnable background,
      @NonNull Runnable foreground,
      @Nullable Runnable last,
      boolean isWaitToTell) {
      mBackground = background;
      mForeground = foreground;
      mLast = last;
      if (isWaitToTell) {
        tell(R.string.wait_search);
      }
    }

    @Override
    public void run() {
      super.run();
      mBackground.run();
      mHandler.post(new Runnable() {
        @Override
        public void run() {
          if (isActuallyShown()) {
            mForeground.run();
          }
          if (mLast != null) {
            mLast.run();
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
          tell(R.string.malformed_url_error);
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