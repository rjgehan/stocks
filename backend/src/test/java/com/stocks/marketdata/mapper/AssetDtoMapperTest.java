package com.stocks.marketdata.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AssetType;
import com.stocks.common.domain.LiquidityTier;
import com.stocks.contract.marketdata.model.Asset;
import org.junit.jupiter.api.Test;

/** Review regression (§14): a symbol on a corporate-action hold is reported untradable, so tickets disable up front. */
class AssetDtoMapperTest {

  static final Asset SIMS = new Asset("SIMS", "Sim Split Corp", "NYSE", AssetType.COMMON_STOCK, true, true, true, true,
      false, LiquidityTier.MEGA, true);

  @Test
  void aCorporateActionHoldMakesTheAssetUntradable() {
    assertThat(AssetDtoMapper.toDto(SIMS, null, false).tradable()).isTrue();
    assertThat(AssetDtoMapper.toDto(SIMS, null, true).tradable()).isFalse();
    assertThat(AssetDtoMapper.toDto(SIMS, null, true).fractionable()).isTrue();
  }
}
