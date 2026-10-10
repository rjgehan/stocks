-- V8_1 (WS5, §20.7; provisional number): feed_event.event_kind gains 'JOURNAL'. V1 declared the CHECK inline
-- (PostgreSQL names it feed_event_event_kind_check); drop whichever CHECK of feed_event mentions event_kind, so the
-- step does not depend on the generated name, then add the named replacement.
DO $$
DECLARE c RECORD;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'feed_event'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%event_kind%'
  LOOP
    EXECUTE 'ALTER TABLE feed_event DROP CONSTRAINT ' || quote_ident(c.conname);
  END LOOP;
END $$;
ALTER TABLE feed_event ADD CONSTRAINT ck_feed_event_kind
  CHECK (event_kind IN ('FILL','POSITION_CLOSED','NEW_LEADER','RESET','DEPOSIT','JOINED','JOURNAL'));
