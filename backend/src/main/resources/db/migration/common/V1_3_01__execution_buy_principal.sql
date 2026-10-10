-- =====================================================================================================
-- V1_3_01 (WS3, §8 "Migrations after V1", §6.2): a BUY execution always costs something.
-- Principal rounds against the player (CEILING for a BUY, FLOOR for a SELL), so a BUY of qty > 0 at price > 0 has
-- principal >= 0.01; a SELL may legitimately round to 0.00 (dust). Portable (PostgreSQL 18.6 and H2 2.4).
-- =====================================================================================================
ALTER TABLE execution ADD CONSTRAINT ck_execution_buy_principal CHECK (side = 'SELL' OR principal > 0);
