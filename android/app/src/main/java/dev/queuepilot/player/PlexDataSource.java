package dev.queuepilot.player;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Send Plex credentials only to the enrolled origin; authenticated requests never redirect. */
@androidx.media3.common.util.UnstableApi
final class PlexDataSource extends BaseDataSource {
  private final String origin;
  private final String token;
  private HttpURLConnection connection;
  private InputStream input;
  private Uri uri;
  private long remaining;
  private boolean opened;

  PlexDataSource(String origin, String token) {
    super(true);
    this.origin = PlexCatalog.base(origin);
    this.token = token;
  }

  static DataSource.Factory factory(String origin, String token) {
    return () -> new PlexDataSource(origin, token);
  }

  @Override
  public long open(DataSpec spec) throws IOException {
    transferInitializing(spec);
    URI requested = URI.create(spec.uri.toString()), expected = URI.create(origin);
    if (!expected.getScheme().equals(requested.getScheme())
        || !expected.getAuthority().equals(requested.getAuthority()))
      throw new IOException("Credential destination refused");
    uri = spec.uri;
    connection = (HttpURLConnection) requested.toURL().openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty("X-Plex-Token", token);
    if (spec.position != 0 || spec.length != C.LENGTH_UNSET) {
      String end =
          spec.length == C.LENGTH_UNSET ? "" : Long.toString(spec.position + spec.length - 1);
      connection.setRequestProperty("Range", "bytes=" + spec.position + "-" + end);
    }
    try {
      int code = connection.getResponseCode();
      if (code != 200 && code != 206) throw new IOException("Direct stream returned HTTP " + code);
      if (code == 200 && spec.position != 0)
        throw new IOException("Server did not honor seek range");
      input = connection.getInputStream();
      remaining = spec.length == C.LENGTH_UNSET ? connection.getContentLengthLong() : spec.length;
      if (remaining < 0) remaining = C.LENGTH_UNSET;
      opened = true;
      transferStarted(spec);
      return remaining;
    } catch (IOException failure) {
      close();
      throw failure;
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (remaining == 0) return C.RESULT_END_OF_INPUT;
    int count =
        input.read(
            buffer,
            offset,
            remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining));
    if (count == -1) return C.RESULT_END_OF_INPUT;
    if (remaining != C.LENGTH_UNSET) remaining -= count;
    bytesTransferred(count);
    return count;
  }

  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return connection == null ? Map.of() : connection.getHeaderFields();
  }

  @Override
  public void close() throws IOException {
    try {
      if (input != null) input.close();
    } finally {
      input = null;
      if (connection != null) connection.disconnect();
      connection = null;
      uri = null;
      if (opened) {
        opened = false;
        transferEnded();
      }
    }
  }
}
