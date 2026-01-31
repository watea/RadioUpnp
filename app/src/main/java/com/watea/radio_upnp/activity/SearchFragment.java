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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.service.RadioURL;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SearchFragment extends SearchRootFragment {
  private static final String LOG_TAG = SearchFragment.class.getSimpleName();
  private static final int MAX_RADIOS = 200;
  private static final int MAX_TAGS = 200;
  private final List<String> countries = new ArrayList<>();
  private final List<String> radioTags = new ArrayList<>();
  private final List<String> bitrates = new ArrayList<>();
  private RadioBrowserClient radioBrowserClient;
  private EditText nameEditText;
  private Spinner countrySpinner;
  private Spinner radioTagSpinner;
  private Spinner bitrateSpinner;
  private int selectedBitrate = 0;

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    radioBrowserClient = new RadioBrowserClient(context);
  }

  @Override
  public void onStop() {
    super.onStop();
    // Preferences are stored only if session is valid
    if (isServerAvailable()) {
      getSharedPreferences()
        .edit()
        .putString(getString(R.string.key_country), getCountry())
        .putString(getString(R.string.key_radio_tag), getRadioTag())
        .putString(getString(R.string.key_bitrate), getBitrate())
        .apply();
    }
  }

  @SuppressLint("InflateParams")
  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    final View searchView = super.onCreateView(view, R.layout.view_search);
    countrySpinner = searchView.findViewById(R.id.country_spinner);
    radioTagSpinner = searchView.findViewById(R.id.radio_tag_spinner);
    bitrateSpinner = searchView.findViewById(R.id.rate_spinner);
    nameEditText = searchView.findViewById(R.id.name_edit_text);
  }

  @Override
  protected JSONArray getStations() throws IOException, JSONException {
    return getJSONArray(getRequest());
  }

  @Override
  protected void clearDialog() {
    countries.clear();
    radioTags.clear();
    bitrates.clear();
  }

  @Override
  protected void fetchDialogItems() throws IOException, JSONException {
    bitrates.addAll(Arrays.asList(getResources().getStringArray(R.array.bitrates_array)));
    bitrates.replaceAll(s -> s + getString(R.string.kbps));
    bitrates.add(0, getString(R.string.bitrate));
    fetchList(
      countries,
      httpUrlBuilder -> httpUrlBuilder
        .addPathSegment("json")
        .addPathSegment("countries"),
      getString(R.string.country));
    fetchList(
      radioTags,
      httpUrlBuilder -> httpUrlBuilder
        .addPathSegment("json")
        .addPathSegment("tags")
        .addQueryParameter("order", "stationcount")
        .addQueryParameter("reverse", "true")
        .addQueryParameter("limit", Integer.toString(MAX_TAGS)),
      getString(R.string.radio_tag));
  }

  @Override
  protected void setDialogItems() {
    assert getContext() != null;
    final ArrayAdapter<String> countriesAdapter = new ArrayAdapter<>(getContext(), R.layout.view_spinner_item, countries);
    final ArrayAdapter<String> radioTagsAdapter = new ArrayAdapter<>(getContext(), R.layout.view_spinner_item, radioTags);
    final ArrayAdapter<String> bitratesAdapter = new ArrayAdapter<>(getContext(), R.layout.view_spinner_item, bitrates);
    setSpinner(countrySpinner, countriesAdapter, countries, R.string.key_country);
    setSpinner(radioTagSpinner, radioTagsAdapter, radioTags, R.string.key_radio_tag);
    setSpinner(bitrateSpinner, bitratesAdapter, bitrates, R.string.key_bitrate);
  }

  @Override
  protected boolean validCurrentRadio(@NonNull JSONObject station) throws JSONException {
    // Bitrate shall be filtered by client
    if (station.optInt("bitrate", 0) >= selectedBitrate) {
      name = station.getString("name");
      stream = station.getString("url_resolved");
      homepage = station.optString("homepage", "");
      icon = null;
      final String favicon = station.optString("favicon", "");
      if (!favicon.isEmpty()) {
        try {
          icon = new RadioURL(new URL(favicon)).getBitmap();
        } catch (MalformedURLException malformedURLException) {
          Log.w(LOG_TAG, "validCurrentRadio: icon fetch error");
        }
      }
      return true;
    }
    return false;
  }

  private String getCountry() {
    return getSpinnerValue(countrySpinner);
  }

  private String getRadioTag() {
    return getSpinnerValue(radioTagSpinner);
  }

  private String getBitrate() {
    return getSpinnerValue(bitrateSpinner);
  }

  private String getSpinnerValue(@NonNull Spinner spinner) {
    return (spinner.getSelectedItemPosition() > 0) ? spinner.getSelectedItem().toString() : "";
  }

  private void buildQuery(@NonNull HttpUrl.Builder builder, @NonNull String key, @NonNull String query) {
    if (!query.isEmpty()) {
      builder.addQueryParameter(key, query);
    }
  }

  @NonNull
  private JSONArray getJSONArray(@NonNull Function<HttpUrl.Builder, HttpUrl.Builder> baseUrlBuilder) throws IOException, JSONException {
    try (final Response response = radioBrowserClient.search(baseUrlBuilder)) {
      if (response.isSuccessful()) {
        return new JSONArray(response.body().string());
      } else {
        throw new IOException("Unexpected code " + response);
      }
    }
  }

  private void fetchList(@NonNull List<String> list, @NonNull Function<HttpUrl.Builder, HttpUrl.Builder> baseUrlBuilder, @NonNull String select) throws IOException, JSONException {
    final JSONArray array = getJSONArray(baseUrlBuilder);
    for (int i = 0; i < array.length(); i++) {
      final String name = array.getJSONObject(i).optString("name", "");
      if (!name.isEmpty() && !list.contains(name)) {
        list.add(name);
      }
    }
    list.sort(Comparator.naturalOrder());
    list.add(0, select);
  }

  private void setSpinner(
    @NonNull Spinner spinner,
    @NonNull ArrayAdapter<String> arrayAdapter,
    @NonNull List<String> list,
    int key) {
    spinner.setAdapter(arrayAdapter);
    final int position = list.indexOf(getSharedPreferences().getString(getString(key), ""));
    spinner.setSelection(Math.max(position, 0));
  }

  @NonNull
  private Function<HttpUrl.Builder, HttpUrl.Builder> getRequest() {
    final String bitrate = getBitrate();
    selectedBitrate = 0;
    try {
      selectedBitrate = Integer.parseInt(bitrate.replace(getString(R.string.kbps), ""));
    } catch (NumberFormatException numberFormatException) {
      Log.w(LOG_TAG, "getRequest: invalid bitrate format: " + bitrate);
    }
    return httpUrlBuilder -> {
      httpUrlBuilder
        .addPathSegment("json")
        .addPathSegment("stations")
        .addPathSegment("search");
      buildQuery(httpUrlBuilder, "country", getCountry());
      buildQuery(httpUrlBuilder, "tag", getRadioTag());
      buildQuery(httpUrlBuilder, "name", nameEditText.getText().toString().trim());
      httpUrlBuilder.addQueryParameter("limit", Integer.toString(MAX_RADIOS));
      return httpUrlBuilder;
    };
  }

  private static class RadioBrowserClient {
    private static final String ALL_HOSTS = "all.api.radio-browser.info";
    private static final long CONNECT = 5;
    private static final long READ = 10;
    @NonNull
    private final OkHttpClient client;
    private final Random random = new Random();
    @Nullable
    private HttpUrl.Builder lastValidHttpUrlBuilder = null; // Cache

    public RadioBrowserClient(@NonNull Context context) {
      final String userAgent = context.getString(R.string.app_name)
        + "/" + BuildConfig.VERSION_NAME
        + " (Android)";
      client = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(CONNECT))
        .readTimeout(Duration.ofSeconds(READ))
        .addInterceptor(chain -> chain.proceed(
          chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .build()))
        .build();
    }

    @NonNull
    public Response search(@NonNull Function<HttpUrl.Builder, HttpUrl.Builder> baseUrlBuilder) throws IOException {
      IOException last = null;
      for (int attempt = 0; attempt < 10; attempt++) {
        try {
          final HttpUrl base = baseUrlBuilder.apply(lastValidHttpUrlBuilder = (lastValidHttpUrlBuilder == null) ? pickBaseUrlBuilder() : lastValidHttpUrlBuilder).build();
          final Response response = client.newCall(new Request.Builder().url(base).get().build()).execute();
          if (response.isSuccessful()) {
            return response;
          } else {
            last = new IOException("Unexpected code " + response);
            lastValidHttpUrlBuilder = null;
            response.close();
          }
        } catch (IOException iOException) {
          last = iOException;
        }
      }
      lastValidHttpUrlBuilder = null;
      throw last;
    }

    @NonNull
    private HttpUrl.Builder pickBaseUrlBuilder() throws UnknownHostException {
      final InetAddress[] ips = InetAddress.getAllByName(ALL_HOSTS);
      if (ips.length == 0) {
        throw new UnknownHostException(ALL_HOSTS);
      }
      final InetAddress ip = ips[random.nextInt(ips.length)];
      final String host = ip.getCanonicalHostName();
      return new HttpUrl.Builder().scheme("https").host(host);
    }
  }
}