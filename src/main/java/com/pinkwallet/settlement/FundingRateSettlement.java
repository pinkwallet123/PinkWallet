package com.pinkwallet.settlement;

import com.pinkwallet.common.redis.RedisConstants;
import com.pinkwallet.contract.entity.ContractPositionDO;
import com.pinkwallet.contract.entity.ContractSymbolConfigDO;
import com.pinkwallet.settlement.RedisClient;
import com.pinkwallet.settlement.enums.OrderSide;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Funding rate fee calculation service.
 * 
 * Computes periodic funding fees for contract positions based on funding rate and position value.
 * Long positions pay fees (negative), short positions receive fees (positive).
 */
public final class FundingRateSettlement {

    private static final Logger logger = LoggerFactory.getLogger(FundingRateSettlement.class);
    private static final int FUNDING_FEE_SCALE = 3;
    private static final RoundingMode FUNDING_FEE_ROUNDING_MODE = RoundingMode.UP;

    private FundingRateSettlement() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Loads market index price from Redis cache.
     * 
     * @param symbolUpper uppercase symbol name
     * @return index price, or null if not found
     */
    public static BigDecimal loadIndexPriceOrNull(String symbolUpper) {
        Validate.notBlank(symbolUpper, "Symbol name cannot be null or empty");

        Object priceObj = RedisClient.get(String.format(RedisConstants.CONTRACT_MARKET_PRICE_SYMBOL, symbolUpper));
        if (priceObj == null) {
            return null;
        }
        try {
            BigDecimal price = new BigDecimal(String.valueOf(priceObj));
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("Index price is non-positive: symbol={}, price={}", symbolUpper, price);
                return null;
            }
            return price;
        } catch (NumberFormatException ex) {
            logger.error("Failed to parse index price: symbol={}, raw={}", symbolUpper, priceObj, ex);
            return null;
        }
    }

    /**
     * Computes and sets funding fee for each position.
     * 
     * Fee = position_value * funding_rate
     * Long positions: fee is negative (pay)
     * Short positions: fee is positive (receive)
     * 
     * @param cfg contract configuration
     * @param fundingRate current funding rate
     * @param positions list of positions to compute fees for
     */
    public static void computeCapitalFee(ContractSymbolConfigDO cfg, BigDecimal fundingRate, List<ContractPositionDO> positions) {
        if (cfg == null || positions == null || positions.isEmpty()) {
            return;
        }
        Validate.notNull(cfg.getMultiplier(), "Contract multiplier cannot be null");
        if (fundingRate == null || fundingRate.compareTo(BigDecimal.ZERO) == 0) {
            for (ContractPositionDO p : positions) {
                if (p != null) {
                    p.setCurCapitalAmount(BigDecimal.ZERO);
                }
            }
            return;
        }

        BigDecimal multiplier = cfg.getMultiplier();
        for (ContractPositionDO p : positions) {
            if (p == null) {
                continue;
            }
            BigDecimal positionBalance = safe(p.getAvgPrice())
                    .multiply(safe(p.getVolume()))
                    .multiply(safe(multiplier));

            BigDecimal capitalFee = isLong(p)
                    ? positionBalance.multiply(fundingRate).negate()
                    : positionBalance.multiply(fundingRate);

            p.setCurCapitalAmount(capitalFee.setScale(FUNDING_FEE_SCALE, FUNDING_FEE_ROUNDING_MODE));
        }
    }

    private static boolean isLong(ContractPositionDO p) {
        String side = p.getSide();
        if (side == null || side.isEmpty()) {
            return false;
        }
        return OrderSide.BUY == OrderSide.fromValue(side);
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
