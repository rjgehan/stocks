-- V4 (Phase 2 wave A; number final since the wave A integration merge, §8 "As
-- deployed"). Phase 2 league slice (WS4): feed reactions (§16.11). One reaction per player per feed item; reacting with
-- another emoji replaces it (cast-everything MERGE in FeedReactionRepository), removing it deletes the row.
-- Stats, the race chart and P&L analysis read existing tables only (§16.8–16.10).
CREATE TABLE feed_reaction (
  feed_event_id  BIGINT      NOT NULL REFERENCES feed_event(id),
  player_id      BIGINT      NOT NULL REFERENCES player(id),
  reaction       VARCHAR(8)  NOT NULL CHECK (reaction IN ('ROCKET','FIRE','GEM','CLAP','LAUGH','SKULL')),
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (feed_event_id, player_id)
);
CREATE INDEX ix_feed_reaction_player ON feed_reaction(player_id);
