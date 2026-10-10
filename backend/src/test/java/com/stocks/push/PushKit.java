package com.stocks.push;

import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.config.StocksProperties;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.io.IOException;

/**
 * Web Push wired without Spring on a fresh database (H2, or PostgreSQL under {@code -Ppg-it}), sending to a
 * {@link FakePushService} on 127.0.0.1 ({@code allow-insecure-endpoints}). The VAPID key is generated into
 * {@code push_vapid_key} on first use, as in production.
 */
final class PushKit implements AutoCloseable {

  final MutableTimeSource time = TestTimes.clock();
  final TestDb db = TestDb.fresh();
  final Fixtures fx = new Fixtures(db.jdbc(), time);
  final FakePushService service;
  final PushProperties props;
  final VapidKeys keys;
  final PushSubscriptions subscriptions;
  final PushSender sender;
  final PushDispatcher dispatcher;
  final PushService push;

  PushKit() throws IOException {
    this(PushProperties.defaults().insecureForTests());
  }

  PushKit(PushProperties props) throws IOException {
    this.props = props;
    service = new FakePushService();
    StocksProperties stocks = TestProperties.stocks();
    keys = new VapidKeys(db.jdbc(), time, props, stocks);
    subscriptions = new PushSubscriptions(db.jdbc());
    sender = new PushSender(keys, props, time);
    dispatcher = new PushDispatcher(subscriptions, sender, JacksonConfig.newMapper(), time, props);
    push = new PushService(subscriptions, dispatcher, keys, new PublicId(time), time, props);
  }

  @Override
  public void close() {
    dispatcher.destroy();
    service.close();
    if (db.dataSource() instanceof com.zaxxer.hikari.HikariDataSource pool) {
      pool.close();
    }
  }
}
