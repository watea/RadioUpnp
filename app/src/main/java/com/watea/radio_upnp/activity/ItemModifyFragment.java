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
import android.os.AsyncTask;
import android.os.Bundle;
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
  private static final String DAR_FM_WEB_PAGE = "web_page";
  private static final String DAR_FM_ID = "id";
  private static final Pattern PATTERN = Pattern.compile(".*(http.*\\.(png|jpg)).*");
  // <HMI assets
  private EditText mNameEditText;
  private EditText mUrlEditText;
  private EditText mWebPageEditText;
  private RadioButton mDarFmRadioButton;
  private ImageButton mSearchImageButton;
  private ProgressBar mProgressBar;
  private UrlWatcher mUrlWatcher;
  private UrlWatcher mWebPageWatcher;
  // />
  // Default values; ADD mode
  private Radio mRadio = null;
  private String mRadioName = null;
  private String mRadioUrl = null;
  private String mRadioWebPage = null;
  private Bitmap mRadioIcon = null;

  @NonNull
  private static String extractValue(@NonNull Element element, @NonNull String tag) {
    return element.getElementsByTag(tag).first().ownText();
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Restore saved state, if any
    if (savedInstanceState != null) {
      mRadio = mRadioLibrary.getFrom(savedInstanceState.getLong(getString(R.string.key_radio_id)));
      mRadioName = savedInstanceState.getString(getString(R.string.key_radio_name));
      mRadioUrl = savedInstanceState.getString(getString(R.string.key_radio_url));
      mRadioWebPage = savedInstanceState.getString(getString(R.string.key_radio_web_page));
      mRadioIcon = BitmapFactory.decodeFile(
        savedInstanceState.getString(getString(R.string.key_radio_icon_file)));
    }
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
      if (mUrlWatcher.mUrl == null) {
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

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    // Inflate the view so that graphical objects exists
    View view = inflater.inflate(R.layout.content_item_modify, container, false);
    mNameEditText = view.findViewById(R.id.name_edit_text);
    mProgressBar = view.findViewById(R.id.progress_bar);
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
            //noinspection unchecked
            new DarFmSearcher().execute();
          } else {
            showSearchButton(false);
            new IconSearcher().execute(mWebPageWatcher.mUrl);
          }
        }
      }
    });
    showSearchButton(true);
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
          if (mUrlWatcher.mUrl == null) {
            tell(R.string.connection_test_aborted);
          } else {
            new UrlTester().execute(mUrlWatcher.mUrl);
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
    mNameEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
      null,
      new BitmapDrawable(
        getResources(),
        Bitmap.createScaledBitmap(mRadioIcon, RADIO_ICON_SIZE, RADIO_ICON_SIZE, false)),
      null,
      null);
  }

  private void showSearchButton(boolean isShowing) {
    mSearchImageButton.setVisibility(isShowing ? View.VISIBLE : View.INVISIBLE);
    mProgressBar.setVisibility(isShowing ? View.INVISIBLE : View.VISIBLE);
  }

  private void tellWait() {
    flushKeyboard(Objects.requireNonNull(getView()));
    tell(R.string.wait_search);
  }

  private void tellSearch() {
    showSearchButton(false);
    tellWait();
  }

  // Utility class to listen for URL edition
  private class UrlWatcher implements TextWatcher {
    private final int mDefaultColor;
    @NonNull
    private final EditText mEditText;
    @Nullable
    private URL mUrl;

    private UrlWatcher(@NonNull EditText editText) {
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
        if (mUrl != null) {
          tell(R.string.malformed_url_error);
        }
        mUrl = null;
        mEditText.setTextColor(ContextCompat.getColor(getActivity(), R.color.colorError));
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class IconSearcher extends AsyncTask<URL, Void, Bitmap> {
    @Override
    protected Bitmap doInBackground(URL... urls) {
      Bitmap foundIcon = null;
      try {
        Element head = Jsoup.connect(urls[0].toString()).get().head();
        // Parse site data
        Matcher matcher = PATTERN.matcher(head.toString());
        if (matcher.find()) {
          foundIcon = NetworkTester.getBitmapFromUrl(new URL(matcher.group(1)));
        }
      } catch (Exception exception) {
        Log.i(LOG_TAG, "Error performing radio site search");
      }
      return foundIcon;
    }

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      tellSearch();
    }

    @Override
    protected void onPostExecute(Bitmap foundIcon) {
      super.onPostExecute(foundIcon);
      if (isActuallyAdded()) {
        showSearchButton(true);
        if (foundIcon == null) {
          tell(R.string.no_icon_found);
        } else {
          setRadioIcon(foundIcon);
          tell(R.string.icon_updated);
        }
      }
    }
  }

  // Launch with no parameter: display the list result or fill content if only one result.
  // Launch with one parameter: fill content.
  @SuppressLint("StaticFieldLeak")
  private class DarFmSearcher
    extends AsyncTask<Map<String, String>, Void, List<Map<String, String>>> {
    @Nullable
    private Bitmap mFoundIcon;

    @SafeVarargs
    @Override
    protected final List<Map<String, String>> doInBackground(Map<String, String>... radios) {
      List<Map<String, String>> darFmRadios = new Vector<>();
      mFoundIcon = null;
      if (radios.length == 0) {
        try {
          Element search = Jsoup
            .connect(DAR_FM_PLAYLIST_REQUEST + getRadioName().replace(" ", SPACE_FOR_SEARCH) +
              WILDCARD + DAR_FM_PARTNER_TOKEN)
            .get();
          // Parse data
          for (Element station : search.getElementsByTag("station")) {
            Map<String, String> map = new Hashtable<>();
            map.put(DAR_FM_ID, extractValue(station, "station_id"));
            map.put(DAR_FM_NAME, extractValue(station, "callsign"));
            darFmRadios.add(map);
          }
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR_FM_PLAYLIST_REQUEST search", iOexception);
        }
      } else {
        darFmRadios.add(radios[0]);
      }
      // When radio is known, fetch data
      if (darFmRadios.size() == 1) {
        Map<String, String> foundRadio = darFmRadios.get(0);
        try {
          Element station = Jsoup
            .connect(DAR_FM_STATIONS_REQUEST + foundRadio.get(DAR_FM_ID) + DAR_FM_PARTNER_TOKEN)
            .get();
          foundRadio.put(DAR_FM_WEB_PAGE, extractValue(station, "websiteurl"));
          mFoundIcon = NetworkTester.getBitmapFromUrl(new URL(extractValue(station, "imageurl")));
        } catch (MalformedURLException malformedURLException) {
          Log.i(LOG_TAG, "Error performing icon search");
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR_FM_STATIONS_REQUEST search", iOexception);
        }
      }
      return darFmRadios;
    }

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      tellSearch();
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onPostExecute(@NonNull final List<Map<String, String>> darFmRadios) {
      super.onPostExecute(darFmRadios);
      if (isActuallyAdded()) {
        switch (darFmRadios.size()) {
          case 0:
            tell(R.string.dar_fm_failed);
            break;
          case 1:
            Map<String, String> foundRadio = darFmRadios.get(0);
            mNameEditText.setText(
              Objects.requireNonNull(foundRadio.get(DAR_FM_NAME)).toUpperCase());
            mUrlEditText.setText(DAR_FM_BASE_URL + foundRadio.get(DAR_FM_ID));
            boolean isDarFmWebPageFound = foundRadio.containsKey(DAR_FM_WEB_PAGE);
            boolean isIconFound = (mFoundIcon != null);
            if (isDarFmWebPageFound) {
              mWebPageEditText.setText(foundRadio.get(DAR_FM_WEB_PAGE));
            }
            if (isIconFound) {
              setRadioIcon(mFoundIcon);
            }
            tell((isIconFound && isDarFmWebPageFound) ?
              R.string.dar_fm_done : R.string.dar_fm_went_wrong);
            break;
          default:
            new AlertDialog
              .Builder(getActivity())
              .setAdapter(
                new SimpleAdapter(
                  getActivity(),
                  darFmRadios,
                  R.layout.row_darfm_radio,
                  new String[]{DAR_FM_NAME},
                  new int[]{R.id.row_darfm_radio_name_text_view}),
                new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialogInterface, int i) {
                    //noinspection unchecked
                    new DarFmSearcher().execute(darFmRadios.get(i));
                  }
                })
              .setCancelable(true)
              .create()
              .show();
        }
        showSearchButton(true);
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class UrlTester extends AsyncTask<URL, Void, String> {
    @Override
    protected String doInBackground(URL... urls) {
      String streamContent = null;
      HttpURLConnection httpURLConnection = null;
      try {
        httpURLConnection = NetworkTester.getActualHttpURLConnection(urls[0]);
        // Actual connection test: header must be audio/mpeg
        streamContent = httpURLConnection.getHeaderField("Content-Type");
        // If we get there, connection has occurred
        Log.d(LOG_TAG, "Connection test status/contentType: " +
          httpURLConnection.getResponseCode() + "/" + streamContent);
      } catch (IOException iOException) {
        // Fires also in case of timeout
        Log.i(LOG_TAG, "Radio URL IO exception");
      } finally {
        if (httpURLConnection != null) {
          httpURLConnection.disconnect();
        }
      }
      return streamContent;
    }

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      tellWait();
    }

    @Override
    protected void onPostExecute(String streamContent) {
      super.onPostExecute(streamContent);
      if (isActuallyAdded()) {
        if ((streamContent == null) || !streamContent.contains("audio/")) {
          tell(R.string.connection_test_failed);
        } else {
          tell(getResources().getString(R.string.connection_test_successful) + streamContent + ".");
        }
      }
    }
  }
}