-- V11: the league's default starting cash becomes $500,000.00 (the owner's decision, 2026-10-14; was $500.00). It
-- funds players created from now on; existing accounts keep theirs (the owner tops them up with Admin -> Deposit to
-- all). Portable H2 2.4 + PostgreSQL 18. On a fresh database app_setting is still empty here (SettingsService seeds it
-- at boot from STARTING_CASH_DEFAULT, whose default is now 500000.00), so this UPDATE changes nothing there.
UPDATE app_setting
   SET setting_value = '500000.00', updated_by = NULL, updated_at = CURRENT_TIMESTAMP
 WHERE setting_key = 'league.defaultStartingCash';
