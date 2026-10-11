package com.stocks.realtime.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * §19.3: once the container has finished a stream's response (the client went away, an error, the async timeout),
 * the sender writes nothing more and does not complete it again (that would dispatch a second "response not usable"
 * error for a client that is gone).
 */
class StreamControllerSinkTest {

  @Test
  void writesAndCompletesWhileTheResponseIsOpen() throws Exception {
    ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
    StreamController.EmitterSink sink = new StreamController.EmitterSink(emitter);
    sink.send("event: hello\ndata: {}\n\n");
    sink.close();
    verify(emitter).send("event: hello\ndata: {}\n\n", StreamController.EVENT_STREAM);
    verify(emitter).complete();
  }

  @Test
  void afterTheContainerFinishedNothingIsWrittenOrCompleted() throws Exception {
    ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
    StreamController.EmitterSink sink = new StreamController.EmitterSink(emitter);
    sink.gone();
    assertThatThrownBy(() -> sink.send(": hb\n\n")).isInstanceOf(IOException.class);
    sink.close();
    verify(emitter, never()).send(any(), any());
    verify(emitter, times(0)).complete();
  }
}
