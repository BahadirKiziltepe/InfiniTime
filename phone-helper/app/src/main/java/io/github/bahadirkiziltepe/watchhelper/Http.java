package io.github.bahadirkiziltepe.watchhelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One web request, start to finish. Never call it on the main thread. */
final class Http {
  private static final int CONNECT_TIMEOUT_MS = 8_000;
  private static final int READ_TIMEOUT_MS = 12_000;
  /** Spotify's answers are small. Anything bigger than this is not read to the end. */
  private static final int MAX_BODY = 4 * 1024 * 1024;

  static final class Reply {
    /** The answer's status, such as 200 or 404. */
    final int code;
    final String body;
    /** Seconds the server asked to wait before trying again, or -1. */
    final int retryAfter;

    Reply(int code, String body, int retryAfter) {
      this.code = code;
      this.body = body;
      this.retryAfter = retryAfter;
    }

    boolean ok() {
      return code >= 200 && code < 300;
    }
  }

  private Http() {
  }

  /**
   * Sends one request and reads the whole answer. {@code bearer} is an access token or null.
   * {@code body} may be null; PUT and POST then go out with an empty body, which Spotify insists on
   * being announced.
   */
  static Reply send(String method, String address, String bearer, String contentType, byte[] body)
      throws IOException {
    HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
    try {
      http.setConnectTimeout(CONNECT_TIMEOUT_MS);
      http.setReadTimeout(READ_TIMEOUT_MS);
      http.setRequestMethod(method);
      http.setUseCaches(false);
      http.setRequestProperty("Accept", "application/json");
      if (bearer != null) {
        http.setRequestProperty("Authorization", "Bearer " + bearer);
      }
      boolean sends = !"GET".equals(method);
      if (sends) {
        byte[] out = body == null ? new byte[0] : body;
        if (contentType != null) {
          http.setRequestProperty("Content-Type", contentType);
        }
        http.setDoOutput(true);
        http.setFixedLengthStreamingMode(out.length);
        try (OutputStream stream = http.getOutputStream()) {
          stream.write(out);
        }
      }
      int code = http.getResponseCode();
      int retryAfter = -1;
      try {
        String wait = http.getHeaderField("Retry-After");
        if (wait != null) {
          retryAfter = Integer.parseInt(wait.trim());
        }
      } catch (NumberFormatException e) {
        // Left at -1.
      }
      InputStream stream = code >= 400 ? http.getErrorStream() : http.getInputStream();
      return new Reply(code, stream == null ? "" : read(stream), retryAfter);
    } finally {
      http.disconnect();
    }
  }

  private static String read(InputStream stream) throws IOException {
    try (InputStream in = stream) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0 && out.size() < MAX_BODY) {
        out.write(buffer, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
