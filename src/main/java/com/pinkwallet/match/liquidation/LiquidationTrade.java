package com.pinkwallet.match.liquidation;

import com.pinkwallet.common.exception.WarnException;
import com.pinkwallet.common.util.Snowflakes;
import com.pinkwallet.common.util.date.DateUtil;
import com.pinkwallet.contract.entity.ContractSymbolConfigDO;
import com.pinkwallet.contract.entity.ContractTradeAllDO;
import com.pinkwallet.contract.entity.ContractTradeDO;
import com.pinkwallet.contract.enums.RoleEnum;
import com.pinkwallet.contract.enums.OrderSideEnum;
import com.pinkwallet.contract.service.ContractFinancialMathService;
import com.pinkwallet.contract.service.IContractTradeService;
import com.pinkwallet.match.liquidation.entity.CoOrder;
import com.pinkwallet.match.liquidation.entity.CoTrade;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;

/**
 * Trade persistence service for liquidation and settlement flows.
 * 
 * Converts matching engine trade events into database records, 
 * persists trade data to both trade table and trade_all table.
 * Calculates trade fees and determines maker/taker roles.
 */
public final class LiquidationTrade {

    private static final Logger logger = LoggerFactory.getLogger(LiquidationTrade.class);
    private static final long ONLY_HUMAN_UID_THRESHOLD = 100000L;

    private LiquidationTrade() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    public static ContractTradeDO persistTrade(
            ContractSymbolConfigDO symbol,
            ContractFinancialMathService math,
            IContractTradeService tradeService,
            java.util.function.Consumer<ContractTradeAllDO> tradeAllWriter,
            CoTrade trade,
            CoOrder selfOrder,
            CoOrder counterOrder
    ) {
        Validate.notNull(symbol, "symbol cannot be null");
        Validate.notNull(math, "math cannot be null");
        Validate.notNull(tradeService, "tradeService cannot be null");
        Validate.notNull(tradeAllWriter, "tradeAllWriter cannot be null");
        Validate.notNull(trade, "trade cannot be null");
        Validate.notNull(selfOrder, "selfOrder cannot be null");
        Validate.notNull(counterOrder, "counterOrder cannot be null");

        ContractTradeDO row = toTradeRow(symbol, math, trade, selfOrder, counterOrder);
        boolean ok = tradeService.insertTrade(symbol.getContractName(), row);
        if (!ok) {
            throw new WarnException("insertTrade failed, tradeId=" + row.getTradeId());
        }

        ContractTradeAllDO all = toTradeAllRow(symbol, row, selfOrder, counterOrder);
        tradeAllWriter.accept(all);
        return row;
    }

    private static ContractTradeDO toTradeRow(
            ContractSymbolConfigDO symbol,
            ContractFinancialMathService math,
            CoTrade trade,
            CoOrder order,
            CoOrder counterOrder
    ) {
        Validate.notNull(trade.getId(), "trade id cannot be null");

        long nowId = Snowflakes.getNextId();
        int nowSec = DateUtil.getNowSecondStamp();

        ContractTradeDO t = new ContractTradeDO();
        t.setId(nowId);
        t.setCreateAt(nowSec);
        t.setUpdateAt(nowSec);

        t.setTradeId(trade.getId());
        t.setTradeTime(resolveTradeTime(trade));

        t.setOrderId(order.getId());
        t.setCounterOrderId(counterOrder.getId());

        t.setOrderKind(order.getOpen());
        t.setOrderSide(order.getSide());
        t.setOrderType(order.getType());
        t.setUid(order.getUid());

        t.setTradeVolume(trade.getVolume());
        t.setTradePrice(trade.getPrice());

        String trendSide = resolveTrendSide(trade, order);
        t.setTradeFee(math.tradeFee(
                trade.getPrice(),
                trade.getVolume(),
                symbol.getMultiplier(),
                order.toContractOrderDO(),
                trendSide
        ));

        t.setOrderRole(trendSide.equals(order.getSide())
                ? RoleEnum.TAKER.getValue()
                : RoleEnum.MAKER.getValue());
        t.setOrderSource(order.getSource());
        t.setCounterUid(counterOrder.getUid());
        t.setOnlyHumanTrade(order.getUid() > ONLY_HUMAN_UID_THRESHOLD
                && counterOrder.getUid() > ONLY_HUMAN_UID_THRESHOLD);
        return t;
    }

    private static ContractTradeAllDO toTradeAllRow(ContractSymbolConfigDO symbol, ContractTradeDO t, CoOrder order, CoOrder counterOrder) {
        ContractTradeAllDO all = new ContractTradeAllDO();
        all.setId(t.getId());
        all.setCreateAt(t.getCreateAt());
        all.setUpdateAt(t.getUpdateAt());
        all.setContractId(symbol.getId());
        all.setContractName(symbol.getContractName());

        all.setTradeId(t.getTradeId());
        all.setTradeTime(t.getTradeTime());

        all.setOrderId(t.getOrderId());
        all.setCounterOrderId(t.getCounterOrderId());

        all.setOrderKind(t.getOrderKind());
        all.setOrderSide(t.getOrderSide());
        all.setOrderType(t.getOrderType());

        all.setUid(t.getUid());
        all.setTradeVolume(t.getTradeVolume());
        all.setTradePrice(t.getTradePrice());
        all.setTradeFee(t.getTradeFee());
        all.setOrderRole(t.getOrderRole());
        all.setOrderSource(t.getOrderSource());

        all.setCounterUid(counterOrder.getUid());
        all.setOnlyHumanTrade(t.getOnlyHumanTrade());
        return all;
    }

    /**
     * Resolves the trend side (buy/sell direction) for fee calculation.
     * Uses trade trend side if available, otherwise infers from order side.
     */
    private static String resolveTrendSide(CoTrade trade, CoOrder order) {
        if (trade.getTrendSide() != null && !trade.getTrendSide().isEmpty()) {
            return trade.getTrendSide();
        }
        String orderSide = order.getSide();
        return OrderSideEnum.BUY.getValue().equals(orderSide)
                ? OrderSideEnum.BUY.getValue()
                : OrderSideEnum.SELL.getValue();
    }

    private static long resolveTradeTime(CoTrade trade) {
        if (trade.getCtime() == null) {
            long fallback = System.currentTimeMillis();
            logger.warn("Trade ctime is null, using current time: tradeId={}", trade.getId());
            return fallback;
        }
        return trade.getCtime().toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
