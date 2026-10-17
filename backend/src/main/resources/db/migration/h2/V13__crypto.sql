-- V13 (§29, the H2 half; the PostgreSQL half is postgresql/V13__crypto.sql): crypto pairs. V1 declared these CHECKs
-- inline, so H2 named them itself; H2 2.4 derives the names deterministically (verified by replaying V1..V12, which
-- also reproduces V8_1's CONSTRAINT_21EB8C), so they are the same in every dev and test database. A different name
-- fails here loudly instead of leaving the old CHECK in place.
-- 1. asset.asset_type gains CRYPTO.
ALTER TABLE asset DROP CONSTRAINT "CONSTRAINT_58";
ALTER TABLE asset ADD CONSTRAINT ck_asset_type
  CHECK (asset_type IN ('COMMON_STOCK','ETF','ADR','OTHER','UNKNOWN','CRYPTO'));
-- 2. A crypto fill settles on its trade date (no T+1): settle_date >= trade_date, as cash_ledger already allows.
ALTER TABLE execution DROP CONSTRAINT "CONSTRAINT_BEF90B18_5";
ALTER TABLE execution ADD CONSTRAINT ck_execution_settle CHECK (settle_date >= trade_date);
-- 3. Crypto day-end marks (every calendar day while crypto is on) have their own basis.
ALTER TABLE daily_mark DROP CONSTRAINT "CONSTRAINT_5B";
ALTER TABLE daily_mark ADD CONSTRAINT ck_daily_mark_basis
  CHECK (day_end_basis IN ('SIP_1MIN','OFFICIAL_CLOSE','LAST_TRADE','SIMULATED','CRYPTO_1MIN'));
