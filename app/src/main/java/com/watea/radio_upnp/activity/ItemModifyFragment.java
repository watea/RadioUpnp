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
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
public class ItemModifyFragment extends MainActivityFragment {
  private static final String LOG_TAG = ItemModifyFragment.class.getName();
  private static final String DAR_FM_API = "http://api.dar.fm/";
  private static final String DAR_FM_PLAYLIST_REQUEST = DAR_FM_API + "playlist.php?q=@callsign%20";
  private static final String DAR_FM_STATIONS_REQUEST = DAR_FM_API + "darstations.php?station_id=";
  private static final String WILDCARD = "*";
  private static final String SPACE_FOR_SEARCH = "%20";
  private static final String DAR_FM_PARTNER_TOKEN = "&partner_token=6453742475";
  private static final String DAR_FM_BASE_URL = "http://stream.dar.fm/";
  private static final String DAR_FM_NAME = "name";
  private static final String DAR_FM_ID = "id";
  private static final Pattern PATTERN = Pattern.compile(".*(http.*\\.(png|jpg)).*");
  private final Handler mHandler = new Handler();
  private final List<Map<String, String>> mDarFmRadios = new Vector<>();
  // <HMI assets
  private EditText mNameEditText;
  private EditText mUrlEditText;
  private EditText mWebPageEditText;
  private RadioButton mDarFmRadioButton;
  private ImageButton mSearchImageButton;
  private ProgressBar mProgressBar;
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
        Matcher matcher = PATTERN.matcher(head.toString());
        if (matcher.find()) {
          mFoundIcon = NetworkTester.getBitmapFromUrl(new URL(matcher.group(1)));
        }
      } catch (Exception exception) {
        Log.i(LOG_TAG, "Error performing radio site search");
      }
    }
  };
  private final Runnable mIconSearchForeground = new Runnable() {
    @Override
    public void run() {
      showSearchButton(true);
      if (mFoundIcon == null) {
        tell(R.string.no_icon_found);
      } else {
        setRadioIcon(mFoundIcon);
        tell(R.string.icon_updated);
      }
    }
  };
  private String mDarFmWebPage;
  // Generic search if list empty
  private final Runnable mDarFmSearchBackground = new Runnable() {
    @Override
    public void run() {
      if (mDarFmRadios.isEmpty()) {
        try {
          Element search = Jsoup
            .connect(
              DAR_FM_PLAYLIST_REQUEST + getRadioName().replace(" ", SPACE_FOR_SEARCH) +
                WILDCARD + DAR_FM_PARTNER_TOKEN)
            .get();
          // Parse data
          for (Element station : search.getElementsByTag("station")) {
            Map<String, String> map = new Hashtable<>();
            map.put(DAR_FM_ID, extractValue(station, "station_id"));
            map.put(DAR_FM_NAME, extractValue(station, "callsign"));
            mDarFmRadios.add(map);
          }
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR_FM_PLAYLIST_REQUEST search", iOexception);
        }
      }
      // When radio is known, fetch data
      if (mDarFmRadios.size() == 1) {
        mFoundIcon = null;
        mDarFmWebPage = null;
        try {
          Element station = Jsoup
            .connect(
              DAR_FM_STATIONS_REQUEST + mDarFmRadios.get(0).get(DAR_FM_ID) + DAR_FM_PARTNER_TOKEN)
            .get();
          mDarFmWebPage = extractValue(station, "websiteurl");
          mFoundIcon = NetworkTester.getBitmapFromUrl(new URL(extractValue(station, "imageurl")));
        } catch (MalformedURLException malformedURLException) {
          Log.i(LOG_TAG, "Error performing icon search");
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR_FM_STATIONS_REQUEST search", iOexception);
        }
      }
    }
  };
  private SimpleAdapter mDarFmSimpleAdapter;
  private final Runnable mDarFmSearchForeground = new Runnable() {
    @SuppressLint("SetTextI18n")
    @Override
    public void run() {
      showSearchButton(true);
      mDarFmSimpleAdapter.notifyDataSetChanged();
      switch (mDarFmRadios.size()) {
        case 0:
          tell(R.string.dar_fm_failed);
          break;
        case 1:
          Map<String, String> foundRadio = mDarFmRadios.get(0);
          mNameEditText.setText(Objects.requireNonNull(foundRadio.get(DAR_FM_NAME)).toUpperCase());
          mUrlEditText.setText(DAR_FM_BASE_URL + foundRadio.get(DAR_FM_ID));
          if (mDarFmWebPage != null) {
            mWebPageEditText.setText(mDarFmWebPage);
          }
          if (mFoundIcon != null) {
            setRadioIcon(mFoundIcon);
          }
          tell(((mFoundIcon == null) || (mDarFmWebPage == null)) ?
            R.string.dar_fm_went_wrong : R.string.dar_fm_done);
          break;
        default:
          mDarFmAlertDialog.show();
      }
    }
  };
  private String mStreamContent;
  private final Runnable mTestRadioURLBackground = new Runnable() {
    @Override
    public void run() {
      mStreamContent = null;
      HttpURLConnection httpURLConnection = null;
      try {
        httpURLConnection = NetworkTester.getActualHttpURLConnection(mUrlWatcher.mUrl);
        // Actual connection test: header must be audio/mpeg
        mStreamContent = httpURLConnection.getHeaderField("Content-Type");
        // If we get there, connection has occurred
        Log.d(LOG_TAG, "Connection test status/contentType: " +
          httpURLConnection.getResponseCode() + "/" + mStreamContent);
      } catch (IOException iOException) {
        // Fires also in case of timeout
        Log.i(LOG_TAG, "Radio URL IO exception");
      } finally {
        if (httpURLConnection != null) {
          httpURLConnection.disconnect();
        }
      }
    }
  };
  private final Runnable mTestRadioURLForeground = new Runnable() {
    @Override
    public void run() {
      if (mStreamContent == null) {
        tell(R.string.connection_test_failed);
      } else {
        tell(getResources().getString(R.string.connection_test_successful) + mStreamContent + ".");
      }
    }
  };

  @NonNull
  private static String extractValue(@NonNull Element element, @NonNull String tag) {
    return element.getElementsByTag(tag).first().ownText();
  }

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    // Restore saved state, if any
    if (savedInstanceState != null) {
      mRadio = mRadioLibrary.getFrom(savedInstanceState.getLong(getString(R.string.key_radio_id)));
      mRadioName = savedInstanceState.getString(getString(R.string.key_radio_name));
      mRadioUrl = savedInstanceState.getString(getString(R.string.key_radio_url));
      mRadioWebPage = savedInstanceState.getString(getString(R.string.key_radio_web_page));
      mRadioIcon = BitmapFactory.decodeFile(
        savedInstanceState.getString(getString(R.string.key_radio_icon_file)));
    }
    // Inflate the view so that graphical objects exists
    final View view = inflater.inflate(R.layout.content_item_modify, container, false);
    // Fill content including recycler
    mNameEditText = view.findViewById(R.id.name_edit_text);
    mProgressBar = view.findViewById(R.id.progressbar);
    mUrlEditText = view.findViewById(R.id.url_edit_text);
    mWebPageEditText = view.findViewById(R.id.web_page_edit_text);
    mDarFmRadioButton = view.findViewById(R.id.dar_fm_radio_button);
    mSearchImageButton = view.findViewById(R.id.search_image_button);
    mNameEditText.setText(mRadioName);
    setRadioIcon((mRadioIcon == null) ?
      BitmapFactory.decodeResource(getActivity().getResources(), R.drawable.ic_radio) : mRadioIcon);
    mUrlEditText.setText(mRadioUrl);
    mWebPageEditText.setText(mRadioWebPage);
    mUrlWatcher = new UrlWatcher(mUrlEditText);
    mWebPageWatcher = new UrlWatcher(mWebPageEditText);
    mUrlEditText.setText(mRadioUrl);
    mWebPageEditText.setText(mRadioWebPage);
    // Order matters!
    ((RadioGroup) view.findViewById(R.id.search_radio_group)).setOnCheckedChangeListener(
      new RadioGroup.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(RadioGroup group, int checkedId) {
          boolean isDarFmSelected = (checkedId == R.id.dar_fm_radio_button);
          mSearchImageButton.setImageResource(
            isDarFmSelected ? R.drawable.ic_search_black_40dp : R.drawable.ic_image_black_40dp);
          mWebPageEditText.setEnabled(!isDarFmSelected);
          mUrlEditText.setEnabled(!isDarFmSelected);
        }

      });
    mDarFmRadioButton.setChecked(
      (savedInstanceState == null) ||
        savedInstanceState.getBoolean(getString(R.string.key_dar_fm_checked)));
    mSearchImageButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        flushKeyboard(Objects.requireNonNull(getView()));
        if (NetworkTester.isDeviceOffline(getActivity())) {
          tell(R.string.no_internet);
        } else {
          if (mDarFmRadioButton.isChecked()) {
            darFmSearch(null);
          } else {
            showSearchButton(false);
            new Searcher(mIconSearchBackground, mIconSearchForeground).start();
          }
        }
      }
    });
    showSearchButton(true);
    // Build alert dialog for DAR FM
    mDarFmAlertDialog = new AlertDialog
      .Builder(getActivity())
      .setAdapter(
        mDarFmSimpleAdapter = new SimpleAdapter(
          getActivity(),
          mDarFmRadios,
          R.layout.row_darfm_radio,
          new String[]{DAR_FM_NAME},
          new int[]{R.id.row_darfm_radio_name_text_view}),
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

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (NetworkTester.isDeviceOffline(getActivity())) {
          tell(R.string.no_internet);
        } else {
          if (mUrlWatcher.isNull()) {
            tell(R.string.connection_test_aborted);
          } else {
            new Searcher(mTestRadioURLBackground, mTestRadioURLForeground).start();
          }
        }
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_radio_black_24dp;
  }

  @Override
  public int getMenuId() {
    return R.menu.menu_item_modify;
  }

  @Override
  public int getTitle() {
    return isAddMode() ? R.string.title_item_add : R.string.title_item_modify;
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    // "set" values...
    outState.putLong(getString(R.string.key_radio_id), isAddMode() ? -1 : mRadio.getId());
    // ...others
    outState.putString(getString(R.string.key_radio_name), mNameEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_url), mUrlEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_web_page), mWebPageEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_icon_file),
      mRadioLibrary.bitmapToFile(mRadioIcon, Integer.toString(hashCode())).getPath());
    outState.putBoolean(getString(R.string.key_dar_fm_checked), mDarFmRadioButton.isChecked());
  }

  @Override
  public void onPause() {
    flushKeyboard(Objects.requireNonNull(getView()));
    super.onPause();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    flushKeyboard(Objects.requireNonNull(getView()));
    if (item.getItemId() == R.id.action_done) {
      if (mUrlWatcher.isNull()) {
        tell(R.string.radio_definition_error);
      } else {
        if (isAddMode()) {
          mRadio = new Radio(
            getRadioName(),
            null,
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
            Objects.requireNonNull(mRadio.getIconFile()).getName().replace(".png", ""));
          mRadio.setURL(mUrlWatcher.mUrl);
          mRadio.setWebPageURL(mWebPageWatcher.mUrl);
          if (mRadioLibrary.updateFrom(mRadio.getId(), mRadio.toContentValues()) <= 0) {
            Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, updating database");
          }
        }
        // Back to previous fragment
        getFragmentManager().popBackStack();
      }
    } else {
      // If we got here, the user's action was not recognized
      // Invoke the superclass to handle it
      return super.onOptionsItemSelected(item);
    }
    return true;
  }

  // Must be called before MODIFY mode
  public void set(@NonNull Radio radio) {
    mRadio = radio;
    mRadioName = mRadio.getName();
    mRadioUrl = mRadio.getURL().toString();
    URL webPageURL = mRadio.getWebPageURL();
    mRadioWebPage = (webPageURL == null) ? null : webPageURL.toString();
    mRadioIcon = mRadio.getIcon();
  }

  public boolean isAddMode() {
    return (mRadio == null);
  }

  private void darFmSearch(@Nullable Map<String, String> radio) {
    showSearchButton(false);
    mDarFmRadios.clear();
    if (radio != null) {
      mDarFmRadios.add(radio);
    }
    new Searcher(mDarFmSearchBackground, mDarFmSearchForeground).start();
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

  private void showSearchButton(boolean isShowing) {
    mSearchImageButton.setVisibility(isShowing ? View.VISIBLE : View.INVISIBLE);
    mProgressBar.setVisibility(isShowing ? View.INVISIBLE : View.VISIBLE);
  }

  // Utility to properly handle asynchronous actions
  private class Searcher extends Thread {
    private final Runnable mBackground, mForeground;

    private Searcher(@NonNull Runnable background, @NonNull Runnable foreground) {
      mBackground = background;
      mForeground = foreground;
      flushKeyboard(Objects.requireNonNull(getView()));
      tell(R.string.wait_search);
    }

    @Override
    public void run() {
      super.run();
      mBackground.run();
      mHandler.post(new Runnable() {
        @Override
        public void run() {
          if (isActuallyAdded()) {
            mForeground.run();
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
      mEditText.addTextChangedListener(this);
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