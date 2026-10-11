package com.stocks.realtime.stream;

import java.io.IOException;

/** Where a stream's frames go: the HTTP response ({@code StreamController}) or a test's recorder. */
public interface StreamSink {

  /** Writes and flushes one complete SSE frame (it ends with a blank line). */
  void send(String frame) throws IOException;

  /** Ends the response. */
  void close();
}
