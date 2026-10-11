package com.stocks.realtime.stream;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tees the broadcast topics to the SSE stream (§19.3): an interceptor on the STOMP broker channel that hands
 * {@code /topic/market.session}, {@code /topic/leaderboard} and {@code /topic/feed} to {@link StreamHub}, whoever
 * publishes them (WS4 publishes the leaderboard and the feed). Payloads arrive already written by Boot's
 * {@code JsonMapper} (§6.3), so the stream carries the very same JSON. Never blocks and never fails the send.
 */
@Component
public class StreamTopicTap implements ChannelInterceptor {

  private static final Logger log = LoggerFactory.getLogger(StreamTopicTap.class);

  private final ObjectProvider<StreamHub> hub;
  private final JsonMapper json;

  public StreamTopicTap(ObjectProvider<StreamHub> hub, JsonMapper json) {
    this.hub = hub;
    this.json = json;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    try {
      String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
      if (StreamHub.publicRoute(destination) != null) {
        StreamHub h = hub.getIfAvailable();
        if (h != null) {
          h.publishPublic(destination, body(message.getPayload()));
        }
      }
    } catch (RuntimeException e) {
      log.debug("SSE tap failed: {}", e.toString());
    }
    return message;
  }

  private String body(Object payload) {
    if (payload instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    if (payload instanceof String s) {
      return s;
    }
    return json.writeValueAsString(payload);
  }
}
