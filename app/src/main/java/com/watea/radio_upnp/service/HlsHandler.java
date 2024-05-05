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

package com.watea.radio_upnp.service;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
Example for:
http://a.files.bbci.co.uk/media/live/manifesto/audio/simulcast/hls/nonuk/sbr_low/ak/bbc_radio_one.m3u87

=>

#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=101760,CODECS="mp4a.40.5"
http://as-hls-ww-live.akamaized.net/pool_904/live/ww/bbc_radio_one/bbc_radio_one.isml/bbc_radio_one-audio%3d96000.norewind.m3u8

=>

#EXTM3U
#EXT-X-VERSION:3
## Created with Unified Streaming Platform(version=1.8.4)
#EXT-X-MEDIA-SEQUENCE:264869362
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-TARGETDURATION:6
#USP-X-TIMESTAMP-MAP:MPEGTS=7514482080,LOCAL=2023-09-19T22:51:50.400000Z
#EXT-X-PROGRAM-DATE-TIME:2023-09-19T22:51:50.400000Z
#EXTINF:6.4, no desc
bbc_radio_one-audio=96000-264869362.ts
#EXTINF:6.4, no desc
bbc_radio_one-audio=96000-264869363.ts
#EXTINF:6.4, no desc
bbc_radio_one-audio=96000-264869364.ts
#EXTINF:6.4, no desc
bbc_radio_one-audio=96000-264869365.ts
#EXTINF:6.4, no desc
bbc_radio_one-audio=96000-264869366.ts
*/
public class HlsHandler {
  private static final String LOG_TAG = HlsHandler.class.getName();
  private static final String BANDWITH = "BANDWIDTH=";
  private static final String STREAM_INF = "#EXT-X-STREAM-INF:";
  private static final String EXTINF = "#EXTINF:";
  private static final String MPEGURL = "MPEGURL";
  private static final String TARGET_DURATION = "#EXT-X-TARGETDURATION:";
  private static final int DEFAULT = -1;
  private static final int CONNECT_DEFAULT_PAUSE = 6000; // ms
  @NonNull
  private final Callback waitCallback;
  @NonNull
  private final HttpURLConnection httpURLConnection;
  @NonNull
  private final Consumer<URLConnection> headerSetter;
  @NonNull
  private final List<URI> actualSegmentURIs = new Vector<>();
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private int targetDuration = CONNECT_DEFAULT_PAUSE; // ms
  @Nullable
  private URI segmentsURI = null;
  @Nullable
  private URI actualSegmentsURI = null;
  @Nullable
  private URI currentActualSegmentURI = null;
  @Nullable
  private InputStream currentInputStream = null;
  private final InputStream inputStream = new InputStream() {
    @Override
    public int read() throws IOException {
      throw new IOException("Stub!");
    }

    @Override
    public int read(byte[] b) throws IOException {
      // currentInputStream shall be defined here
      if (currentInputStream == null) {
        return DEFAULT;
      }
      int result = currentInputStream.read(b);
      if (result < 0) {
        close();
        openStream(getNextSegmentIndex());
        return read(b);
      }
      return result;
    }

    @Override
    public void close() throws IOException {
      if (currentInputStream != null) {
        currentInputStream.close();
      }
    }
  };
  @Nullable
  private String rate = null;

  // waitCallback allows caller to flush buffer before waiting for data (if not connection may be lost)
  public HlsHandler(
    @NonNull HttpURLConnection httpURLConnection,
    @NonNull Consumer<URLConnection> headerSetter,
    @NonNull Callback waitCallback)
    throws IOException, URISyntaxException {
    this.httpURLConnection = httpURLConnection;
    this.headerSetter = headerSetter;
    this.waitCallback = waitCallback;
    // Fetch first segments URI
    fetchSegmentsURI();
  }

  public static boolean isHls(@NonNull HttpURLConnection httpURLConnection) throws IOException {
    final String streamContentType = RadioURL.getStreamContentType(httpURLConnection);
    return (streamContentType != null) && streamContentType.toUpperCase().contains(MPEGURL);
  }

  // Must be called on release
  public void release() {
    executor.shutdown();
  }

  // rate in b/s
  @Nullable
  public String getRate() {
    return rate;
  }

  // Only InputStream.read(byte[] b) and InputStream.close() method shall be used.
  // Shall only be called once.
  @NonNull
  public InputStream getInputStream() throws IOException, URISyntaxException {
    // Fetch first segments
    if (fetchSegmentsFile()) {
      openStream(0);
      // Cyclically wakeup (Shannon theorem)
      executor.scheduleWithFixedDelay(this::wakeUp, 0, targetDuration / 2, TimeUnit.MILLISECONDS);
    }
    return inputStream;
  }

  synchronized private void wakeUp() {
    notify();
  }

  // Fetch first found URI
  private void fetchSegmentsURI() throws IOException, URISyntaxException {
    processURLConnection(
      httpURLConnection,
      testIf(STREAM_INF, (line1, line2) -> {
        rate = findStringFor(BANDWITH, line1);
        segmentsURI = new URI(line2);
        actualSegmentsURI = httpURLConnection.getURL().toURI().resolve(segmentsURI);
        return true;
      }));
  }

  // Returns DEFAULT if fails
  synchronized private int getNextSegmentIndex() throws IOException {
    Log.d(LOG_TAG, "openNextStream");
    int index = actualSegmentURIs.indexOf(currentActualSegmentURI);
    int tryIndex = 0;
    // Wait if last segment
    while ((index >= 0) && (index == actualSegmentURIs.size() - 1)) {
      try {
        wait();
        // Allow caller buffer to be flushed to avoid connection lost (order matters)
        if (tryIndex++ == 1) {
          waitCallback.run();
        }
        // Fetch new data
        index = fetchSegmentsFile() ? actualSegmentURIs.indexOf(currentActualSegmentURI) : DEFAULT;
      } catch (InterruptedException interruptedException) {
        Log.d(LOG_TAG, "openNextStream:", interruptedException);
      } catch (URISyntaxException uRISyntaxException) {
        Log.d(LOG_TAG, "openNextStream:", uRISyntaxException);
        throw new IOException("openNextStream: error in reading segment URI");
      }
    }
    return ((index >= 0) && (index < actualSegmentURIs.size() - 1)) ? ++index : DEFAULT;
  }

  // Flush currentInputStream if index < 0
  synchronized private void openStream(int index) throws IOException {
    currentActualSegmentURI = (index < 0) ? null : actualSegmentURIs.get(index);
    currentInputStream =
      (currentActualSegmentURI == null) ? null : currentActualSegmentURI.toURL().openStream();
  }

  // Seek URI for segments and all segment data.
  // Returns true if OK.
  private boolean fetchSegmentsFile() throws IOException, URISyntaxException {
    Log.d(LOG_TAG, "fetchSegmentsFile");
    // Reset segments
    actualSegmentURIs.clear();
    if (actualSegmentsURI != null) {
      assert segmentsURI != null;
      // Fetch
      final URLConnection uRLConnection = actualSegmentsURI.toURL().openConnection();
      // Set headers (User-Agent...)
      headerSetter.accept(uRLConnection);
      processURLConnection(
        uRLConnection,
        // Duration of 1 segment
        testIf(TARGET_DURATION, (line1, line2) -> {
          targetDuration = parseIntFor(TARGET_DURATION, line1) * 1000;
          return false;
        }),
        // Segment URI & base URL for segments
        testIf(EXTINF, (line1, line2) -> {
          assert actualSegmentsURI != null;
          actualSegmentURIs.add(actualSegmentsURI.resolve(new URI(line2)));
          return false;
        }));
    }
    return !actualSegmentURIs.isEmpty();
  }

  // Utility to find an integer in a string after key
  @Nullable
  private String findStringFor(@NonNull String key, @NonNull String string) {
    final Pattern pattern = Pattern.compile(".*" + key + "([0-9]*).*");
    final Matcher matcher = pattern.matcher(string);
    return (matcher.find() && (matcher.groupCount() > 0)) ? matcher.group(1) : null;
  }

  private int parseIntIn(@Nullable String string) {
    if (string != null) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException numberFormatException) {
        Log.d(LOG_TAG, "parseString: found malformed number for " + string);
      }
    }
    return DEFAULT;
  }

  /**
   * @noinspection SameParameterValue
   */
  private int parseIntFor(@NonNull String key, @NonNull String string) {
    return parseIntIn(findStringFor(key, string));
  }

  @NonNull
  private Predicate testIf(@NonNull String target, @NonNull Predicate predicate) {
    return (line1, line2) -> line1.startsWith(target) && predicate.test(line1, line2);
  }

  // Utility to parse file content
  private void processURLConnection
  (@NonNull URLConnection uRLConnection,
   @NonNull Predicate... predicates) throws IOException, URISyntaxException {
    try (final BufferedReader bufferedReader =
           new BufferedReader(new InputStreamReader(uRLConnection.getInputStream()))) {
      String line1 = bufferedReader.readLine();
      String line2 = bufferedReader.readLine();
      boolean found = (line1 == null);
      while (!found && (line1 != null)) {
        for (int i = 0; (i < predicates.length) && !found; i++) {
          found = predicates[i].test(line1, line2);
        }
        line1 = line2;
        line2 = bufferedReader.readLine();
      }
    }
  }

  public interface Callback {
    void run() throws IOException;
  }

  private interface Predicate {
    boolean test(@NonNull String line1, @NonNull String line2) throws URISyntaxException;
  }
}