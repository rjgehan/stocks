-- V6 (Phase 2 wave A, ARCHITECTURE §8.6, §15.10): screener statistics per universe symbol, written by the SCREENER_STATS
-- periodic task (WS2) and read by the screener. Portable H2 + PostgreSQL (§8 portability rules); upserts are cast
-- MERGEs (ScreenerStatRepository).
CREATE TABLE screener_stat (
  symbol             VARCHAR(12)              NOT NULL PRIMARY KEY,
  -- 20-day average daily share volume: SIP daily bars (display), IEX daily bars (the basis of a partial IEX volume)
  avg_volume         BIGINT,
  avg_volume_iex     BIGINT,
  avg_volume_days    INTEGER,
  avg_volume_as_of   DATE,
  volume_at          TIMESTAMP WITH TIME ZONE,
  -- company profile (Finnhub profile2, or the SIM): shares and the provider's market cap in shares and dollars
  shares_outstanding NUMERIC(20,0),
  market_cap         NUMERIC(22,0),
  industry           VARCHAR(100),
  profile_source     VARCHAR(12),
  profile_at         TIMESTAMP WITH TIME ZONE,
  updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT ck_screener_stat_volume CHECK (avg_volume IS NULL OR avg_volume >= 0),
  CONSTRAINT ck_screener_stat_volume_iex CHECK (avg_volume_iex IS NULL OR avg_volume_iex >= 0),
  CONSTRAINT ck_screener_stat_shares CHECK (shares_outstanding IS NULL OR shares_outstanding > 0)
);
