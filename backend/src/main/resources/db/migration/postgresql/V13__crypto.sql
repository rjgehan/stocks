-- V13 (§29, the PostgreSQL half; the H2 half is h2/V13__crypto.sql): crypto pairs. V1 declared these CHECKs inline
-- (PostgreSQL names them <table>_<column>_check, or <table>_check for a table CHECK); drop whichever CHECK of the
-- table mentions the column, so the step does not depend on the generated name, then add the named replacement.
DO $$
DECLARE c RECORD;
BEGIN
  FOR c IN SELECT conrelid::regclass::text AS tbl, conname FROM pg_constraint
            WHERE contype = 'c' AND (
                  (conrelid = 'asset'::regclass AND pg_get_constraintdef(oid) LIKE '%asset_type%')
               OR (conrelid = 'execution'::regclass AND pg_get_constraintdef(oid) LIKE '%settle_date%')
               OR (conrelid = 'daily_mark'::regclass AND pg_get_constraintdef(oid) LIKE '%day_end_basis%'))
  LOOP
    EXECUTE 'ALTER TABLE ' || quote_ident(c.tbl) || ' DROP CONSTRAINT ' || quote_ident(c.conname);
  END LOOP;
END $$;
-- 1. asset.asset_type gains CRYPTO.
ALTER TABLE asset ADD CONSTRAINT ck_asset_type
  CHECK (asset_type IN ('COMMON_STOCK','ETF','ADR','OTHER','UNKNOWN','CRYPTO'));
-- 2. A crypto fill settles on its trade date (no T+1): settle_date >= trade_date, as cash_ledger already allows.
ALTER TABLE execution ADD CONSTRAINT ck_execution_settle CHECK (settle_date >= trade_date);
-- 3. Crypto day-end marks (every calendar day while crypto is on) have their own basis.
ALTER TABLE daily_mark ADD CONSTRAINT ck_daily_mark_basis
  CHECK (day_end_basis IN ('SIP_1MIN','OFFICIAL_CLOSE','LAST_TRADE','SIMULATED','CRYPTO_1MIN'));
