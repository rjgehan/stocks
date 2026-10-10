package com.stocks.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Refuses an inbound STOMP frame with an {@code ERROR} frame (§19). The frames of one session are processed in order
 * ({@code preserveReceiveOrder}), and in that mode Spring only logs an exception thrown by an inbound interceptor: the
 * client would never learn that its CONNECT or SUBSCRIBE was refused and would hang. So the interceptors call
 * {@link #reject} instead of throwing: it drops the frame and sends an {@code ERROR} with a {@code message} header
 * ({@code CODE: explanation}, the §7.2 codes) to the session through {@code clientOutboundChannel}; Spring's STOMP
 * handler then closes the connection, as STOMP requires after an ERROR.
 */
@Component
public class StompErrors {

  private static final Logger log = LoggerFactory.getLogger(StompErrors.class);

  private final ObjectProvider<MessageChannel> clientOutboundChannel;

  public StompErrors(@Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> clientOutboundChannel) {
    this.clientOutboundChannel = clientOutboundChannel;
  }

  /**
   * Sends {@code ERROR (reason)} to the frame's session and returns {@code null}, the value a
   * {@code ChannelInterceptor.preSend} returns to drop the frame.
   */
  public Message<?> reject(Message<?> inbound, String reason) {
    String sessionId = SimpMessageHeaderAccessor.getSessionId(inbound.getHeaders());
    log.info("STOMP {}: refused {} ({})", sessionId, SimpMessageHeaderAccessor.getMessageType(inbound.getHeaders()),
        reason);
    MessageChannel channel = clientOutboundChannel.getIfAvailable();
    if (channel != null && sessionId != null) {
      StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
      error.setMessage(reason);
      error.setSessionId(sessionId);
      String receipt = StompHeaderAccessor.wrap(inbound).getReceipt();
      if (receipt != null) {
        error.setReceiptId(receipt);
      }
      channel.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
    }
    return null;
  }
}
