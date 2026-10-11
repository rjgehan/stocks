-- V8_1 (WS5, §20.7; the vendor half of V8): feed_event.event_kind gains 'JOURNAL'. V1 declared the CHECK inline, so H2
-- named it itself; H2 2.4 derives the name deterministically (verified on a fresh V1..V2 schema), so it is the same in
-- every dev and test database. A different name fails here loudly instead of leaving the old CHECK in place.
ALTER TABLE feed_event DROP CONSTRAINT "CONSTRAINT_21EB8C";
ALTER TABLE feed_event ADD CONSTRAINT ck_feed_event_kind
  CHECK (event_kind IN ('FILL','POSITION_CLOSED','NEW_LEADER','RESET','DEPOSIT','JOINED','JOURNAL'));
