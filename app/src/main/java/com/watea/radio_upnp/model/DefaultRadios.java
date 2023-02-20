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

package com.watea.radio_upnp.model;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.R;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DefaultRadios {
  private static final String LOG_TAG = DefaultRadios.class.getName();
  private static final List<DefaultRadio> DEFAULT_RADIOS = Arrays.asList(
    new DefaultRadio(
      "FRANCE INTER",
      R.drawable.logo_france_inter,
      "http://direct.franceinter.fr/live/franceinter-midfi.mp3",
      "https://www.franceinter.fr/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "FRANCE CULTURE",
      R.drawable.logo_france_culture,
      "http://direct.franceculture.fr/live/franceculture-midfi.mp3",
      "https://www.franceculture.fr/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "OUI FM",
      R.drawable.logo_oui_fm,
      "http://broadcast.infomaniak.net/ouifm-high.mp3",
      "https://www.ouifm.fr/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "EUROPE1",
      R.drawable.logo_europe1,
      "http://ais-live.cloud-services.paris:8000/europe1.mp3",
      "https://www.europe1.fr/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "RFM",
      R.drawable.logo_rfm,
      "http://ais-live.cloud-services.paris:8000/rfm.mp3",
      "http://www.rfm.fr/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "SKYROCK",
      R.drawable.logo_skyrock,
      "http://icecast.skyrock.net/s/natio_mp3_128k",
      "https://www.skyrock.com/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "VIRGIN",
      R.drawable.logo_virgin,
      "http://ais-live.cloud-services.paris:8000/virgin.mp3",
      "https://www.virginradio.fr/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "FUN",
      R.drawable.logo_fun,
      "http://icecast.funradio.fr/fun-1-44-128?listen=webCwsBCggNCQgLDQUGBAcGBg",
      "https://www.funradio.fr/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "RADIO PARADISE",
      R.drawable.logo_radio_paradise,
      "http://stream.radioparadise.com/flacm",
      "https://www.radioparadise.com/",
      "audio/mp3",
      192),
    new DefaultRadio(
      "PBB",
      R.drawable.logo_pbb_radio,
      "https://pbbradio.com:8443/128",
      "https://www.allzicradio.com/en/player/listen/2579/pbb-laurent-garnier",
      "audio/mp3",
      192),
    new DefaultRadio(
      "FIP",
      R.drawable.logo_fip,
      "http://icecast.radiofrance.fr/fip-hifi.aac",
      "https://www.fip.fr/",
      "audio/mp3",
      192));

  @NonNull
  public static List<Radio> get(@NonNull Context context, int iconSize) {
    return DEFAULT_RADIOS.stream()
      .map(defaultRadio -> {
        try {
          return defaultRadio.getRadioFrom(context, iconSize);
        } catch (MalformedURLException malformedURLException) {
          Log.e(LOG_TAG, "get: MalformedURLException fired on: " + defaultRadio.name);
          return null;
        }
      })
      .filter(Objects::nonNull)
      .collect(Collectors.toList());
  }

  private static class DefaultRadio {
    @NonNull
    private final String name;
    private final int drawable;
    @NonNull
    private final String uRL;
    @NonNull
    private final String webPageURL;
    @NonNull
    private final String mime;
    private final int quality;

    private DefaultRadio(
      @NonNull String name,
      int drawable,
      @NonNull String uRL,
      @NonNull String webPageURL,
      @NonNull String mime,
      int quality) {
      this.name = name;
      this.drawable = drawable;
      this.uRL = uRL;
      this.webPageURL = webPageURL;
      this.mime = mime;
      this.quality = quality;
    }

    private Radio getRadioFrom(@NonNull Context context, int iconSize)
      throws MalformedURLException {
      return new Radio(
        name,
        Radio.createScaledBitmap(
          BitmapFactory.decodeResource(context.getResources(), drawable), iconSize),
        new URL(uRL),
        new URL(webPageURL),
        mime,
        quality);
    }
  }
}