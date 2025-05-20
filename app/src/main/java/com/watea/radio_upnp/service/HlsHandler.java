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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
-- Example for (indirect):
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


-- Example for (direct):
https://stream.radiofrance.fr/fiprock/fiprock_hifi.m3u8?id=radiofrance:

=>

#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA-SEQUENCE:980291
#EXT-X-TARGETDURATION:4
#EXT-X-START:TIME-OFFSET=0
#EXT-X-PROGRAM-DATE-TIME:2025-03-31T09:48:41Z
#EXTINF:4.000,
/accs3/fiprock/prod1transcoder1/fiprock_aac_hifi_4_980291_1743414521.ts?id=radiofrance
#EXT-X-PROGRAM-DATE-TIME:2025-03-31T09:48:45Z
#EXTINF:4.000,
/accs3/fiprock/prod1transcoder1/fiprock_aac_hifi_4_980292_1743414525.ts?id=radiofrance
#EXT-X-PROGRAM-DATE-TIME:2025-03-31T09:48:49Z
#EXTINF:4.000,
/accs3/fiprock/prod1transcoder1/fiprock_aac_hifi_4_980293_1743414529.ts?id=radiofrance
#EXT-X-PROGRAM-DATE-TIME:2025-03-31T09:48:53Z
#EXTINF:4.000,
/accs3/fiprock/prod1transcoder1/fiprock_aac_hifi_4_980294_1743414533.ts?id=radiofrance
#EXT-X-PROGRAM-DATE-TIME:2025-03-31T09:48:57Z
#EXTINF:4.000,
/accs3/fiprock/prod1transcoder1/fiprock_aac_hifi_4_980295_1743414537.ts?id=radiofrance
#EXT-X-PROGRAM-DATE-TIME:2025-03-31T09:49:01Z
#EXTINF:4.000,
/accs3/fiprock/prod1transcoder1/fiprock_aac_hifi_4_980296_1743414541.ts?id=radiofrance
#EXT-X-PROGRAM-DATE-TIME:2025-03-31T09:49:05Z
#EXTINF:4.000,
/accs3/fiprock/prod1transcoder1/fiprock_aac_hifi_4_980297_1743414545.ts?id=radiofrance
*/
public class HlsHandler {
  private static final String LOG_TAG = HlsHandler.class.getSimpleName();
  private static final String BANDWITH = "BANDWIDTH=";
  private static final String STREAM_INF = "#EXT-X-STREAM-INF:";
  private static final String EXTINF = "#EXTINF:";
  private static final String MPEGURL = "MPEGURL";
  private static final String TARGET_DURATION = "#EXT-X-TARGETDURATION:";
  private static final int DEFAULT = -1;
  @NonNull
  private final HttpURLConnection httpURLConnection;
  @NonNull
  private final Consumer<URLConnection> headerSetter;
  @NonNull
  private final Consumer<String> rateListener;
  @NonNull
  private final List<Segment> actualSegments = new ArrayList<>();
  private int targetDuration = DEFAULT; // ms
  @Nullable
  private URI segmentsURI = null;
  @Nullable
  private URI actualSegmentsURI = null;
  @Nullable
  private Segment currentActualSegment = null;
  @Nullable
  private InputStream currentInputStream = null;
  private long currentInputStreamTime = DEFAULT;
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
        final int index;
        try {
          index = getNextSegmentIndex();
        } catch (URISyntaxException uRISyntaxException) {
          Log.e(LOG_TAG, "read: unable to get next segment index", uRISyntaxException);
          throw new IOException();
        }
        if ((index < 0) || (index >= actualSegments.size())) {
          throw new IOException("read: index is invalid");
        } else {
          openStream(index);
        }
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

  public HlsHandler(
    @NonNull HttpURLConnection httpURLConnection,
    @NonNull Consumer<URLConnection> headerSetter,
    @NonNull Consumer<String> rateListener)
    throws IOException, URISyntaxException {
    this.httpURLConnection = httpURLConnection;
    this.headerSetter = headerSetter;
    this.rateListener = rateListener;
    // Fetch first segments URI
    fetchSegmentsURI();
  }

  public static boolean isHls(@NonNull HttpURLConnection httpURLConnection) throws IOException {
    final String streamContentType = RadioURL.getStreamContentType(httpURLConnection);
    return (streamContentType != null) && streamContentType.toUpperCase().contains(MPEGURL);
  }

  // Only InputStream.read(byte[] b) and InputStream.close() method shall be used.
  // Shall only be called once.
  @NonNull
  public InputStream getInputStream() throws IOException, URISyntaxException {
    // Fetch first segments
    if (fetchSegmentsFile()) {
      openStream(0);
    }
    return inputStream;
  }

  // Fetch first found URI
  void fetchSegmentsURI() throws IOException, URISyntaxException {
    // Indirect...
    if (!processURLConnection(
      httpURLConnection,
      testIf(STREAM_INF, (line1, line2) -> {
        rateListener.accept(findStringFor(BANDWITH, line1)); // Rate in b/s
        segmentsURI = new URI(line2);
        actualSegmentsURI = httpURLConnection.getURL().toURI().resolve(segmentsURI);
        return true;
      }))) {
      // ... or direct
      segmentsURI = httpURLConnection.getURL().toURI();
      actualSegmentsURI = segmentsURI;
    }
  }

  // Return DEFAULT if fails
  private int getNextSegmentIndex() throws IOException, URISyntaxException {
    Log.d(LOG_TAG, "getNextSegmentIndex");
    int index = actualSegments.indexOf(currentActualSegment);
    if (index == actualSegments.size() - 1) {
      if (fetchSegmentsFile()) {
        index = actualSegments.indexOf(currentActualSegment);
        return (index < 0) ? 0 : (index == actualSegments.size() - 1) ? DEFAULT : ++index;
      } else {
        return DEFAULT;
      }
    } else {
      return ++index;
    }
  }

  // Flush currentInputStream if index < 0, else index must be valid
  private void openStream(int index) throws IOException {
    Log.d(LOG_TAG, "openStream: index=" + index);
    // Wait if needed, to avoid reading to fast
    if ((currentActualSegment != null) && (currentActualSegment.getDuration() != DEFAULT)) {
      final long delta = currentInputStreamTime + currentActualSegment.getDuration() - System.currentTimeMillis();
      if (delta > 0) {
        try {
          Log.d(LOG_TAG, "openStream: wait => " + delta + " ms");
          Thread.sleep(delta);
        } catch (InterruptedException InterruptedException) {
          Log.d(LOG_TAG, "openStream: unable to sleep");
        }
      }
    }
    currentActualSegment = actualSegments.get(index);
    currentInputStreamTime = System.currentTimeMillis();
    assert currentActualSegment != null;
    currentInputStream = currentActualSegment.openStream();
  }

  // Seek URI for segments and all segment data.
  // Return true if OK.
  private boolean fetchSegmentsFile() throws IOException, URISyntaxException {
    Log.d(LOG_TAG, "fetchSegmentsFile");
    // Reset segments
    actualSegments.clear();
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
          actualSegments.add(new Segment(
            actualSegmentsURI.resolve(new URI(line2)),
            (long) (parseDoubleFor(EXTINF, line1) * 1000)));
          return false;
        }));
    }
    Log.d(LOG_TAG, "actualSegments size: " + actualSegments.size() + " targetDuration: " + targetDuration + " ms");
    return !actualSegments.isEmpty();
  }

  // Utility to find a number in a string after key
  @Nullable
  private String findStringFor(@NonNull String key, @NonNull String string) {
    final Pattern pattern = Pattern.compile(".*" + key + "(\\d+(?:\\.\\d+)?).*");
    final Matcher matcher = pattern.matcher(string);
    return (matcher.find() && (matcher.groupCount() > 0)) ? matcher.group(1) : null;
  }

  private int parseIntIn(@Nullable String string) {
    if (string != null) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException numberFormatException) {
        Log.d(LOG_TAG, "parseIntIn: found malformed number for " + string);
      }
    }
    return DEFAULT;
  }

  private double parseDoubleIn(@Nullable String string) {
    if (string != null) {
      try {
        return Double.parseDouble(string);
      } catch (NumberFormatException numberFormatException) {
        Log.d(LOG_TAG, "parseLongIn: found malformed number for " + string);
      }
    }
    return DEFAULT;
  }

  @SuppressWarnings("SameParameterValue")
  private int parseIntFor(@NonNull String key, @NonNull String string) {
    return parseIntIn(findStringFor(key, string));
  }

  @SuppressWarnings("SameParameterValue")
  private double parseDoubleFor(@NonNull String key, @NonNull String string) {
    return parseDoubleIn(findStringFor(key, string));
  }

  @NonNull
  private Predicate testIf(@NonNull String target, @NonNull Predicate predicate) {
    return (line1, line2) -> line1.startsWith(target) && predicate.test(line1, line2);
  }

  // Utility to parse file content
  private boolean processURLConnection
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
      return found;
    }
  }

  private interface Predicate {
    boolean test(@NonNull String line1, @NonNull String line2) throws URISyntaxException;
  }

  private static class Segment {
    @NonNull
    private final URI uRI;
    private final long duration;

    public Segment(@NonNull URI uRI, long duration) {
      this.duration = duration;
      this.uRI = uRI;
    }

    public long getDuration() {
      return duration;
    }

    @NonNull
    public InputStream openStream() throws IOException {
      return uRI.toURL().openStream();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) return true;
      if (obj == null || getClass() != obj.getClass()) return false;
      final Segment segment = (Segment) obj;
      return uRI.equals(segment.uRI);
    }

    @Override
    public int hashCode() {
      return uRI.hashCode();
    }
  }
}