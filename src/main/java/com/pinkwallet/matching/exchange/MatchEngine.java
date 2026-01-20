package com.pinkwallet.matching.exchange;

import com.google.common.base.Optional;
import com.pinkwallet.matching.exchange.common.IdGenUtil;
import com.pinkwallet.matching.exchange.common.MathUtil;
import com.pinkwallet.matching.exchange.common.PairConfig;
import com.pinkwallet.matching.exchange.domain.*;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Matches limit/market orders against the opposite order book.
 */
public final class MatchEngine {

    private static final Logger logger = LoggerFactory.getLogger(MatchEngine.class);
    private static final int SLIPPAGE_SCALE = 16;

    private MatchEngine() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Matches a taker order against the opposite order book.
     *
     * <p>Supported order types: {@link OrderType#ORDER_LIMIT}, {@link OrderType#ORDER_MARKET}.</p>
     */
    public Optional<Order> tryMatch(MatchConfig cfg, Order taker, OrderBook makerBook) {
        if (taker == null || cfg == null || makerBook == null) {
            return Optional.fromNullable(taker);
        }
        if (taker.getType() != OrderType.ORDER_LIMIT && taker.getType() != OrderType.ORDER_MARKET) {
            return Optional.of(taker);
        }
        if (taker.isFilled() || makerBook.isEmpty()) {
            return Optional.of(taker);
        }

        switch (taker.getType()) {
            case ORDER_LIMIT:
            case ORDER_MARKET:
                return matchGreedy(cfg, taker, makerBook);
            default:
                return Optional.of(taker);
        }
    }

    private Optional<Order> matchGreedy(MatchConfig cfg, Order taker, OrderBook makerBook) {
        Validate.notNull(cfg, "match config cannot be null");
        Validate.notNull(taker, "taker order cannot be null");
        Validate.notNull(makerBook, "maker book cannot be null");

        synchronized (cfg.getLockOrderBook()) {
            while (!taker.isFilled() && !makerBook.isEmpty()) {
                Optional<Order> topOpt = makerBook.getTopOrder();
                if (!topOpt.isPresent()) {
                    break;
                }
                Order maker = topOpt.get();
                Optional<Trade> tradeOpt = createTradeIfCrosses(taker, maker);
                if (!tradeOpt.isPresent()) {
                    break;
                }

                Trade trade = tradeOpt.get();
                OrderBook.changeTakerOrder(taker, trade);
                trade.setTakerOrder(taker.clone());

                Order updatedMaker = makerBook.changeMakerOrder(maker.getId(), trade);
                trade.setMakerOrder(updatedMaker.clone());

                if (!enforceAndConsumeSlippageBudget(taker, trade)) {
                    logger.debug("Slippage budget exhausted: takerId={}, tradeId={}",
                            taker.getId(), trade.getTradeNonce());
                    break;
                }

                if (updatedMaker.getUnfilledQuantityAtomic().compareTo(PairConfig.MIN_TRADE_VOL) < 0) {
                    // Maker order fully filled or below minimum trade volume
                }
            }
            return Optional.fromNullable(taker);
        }
    }

    private Optional<Trade> createTradeIfCrosses(Order taker, Order maker) {
        if (taker == null || maker == null) {
            return Optional.absent();
        }
        if (maker.getType() != OrderType.ORDER_LIMIT) {
            return Optional.absent();
        }
        if (!OrderSide.getOpposite(taker.getSide()).equals(maker.getSide())) {
            return Optional.absent();
        }

        BigDecimal price = maker.getPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.absent();
        }
        if (taker.getType() == OrderType.ORDER_MARKET) {
            Trade t = createTrade(taker, maker, price, calcMarketVolume(taker, maker, price));
            return Optional.fromNullable(t);
        }
        if (taker.crossesAt(price)) {
            BigDecimal qty = taker.getUnfilledQuantity().min(maker.getUnfilledQuantity());
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                Trade t = createTrade(taker, maker, price, qty);
                return Optional.fromNullable(t);
            }
        }
        return Optional.absent();
    }

    private BigDecimal calcMarketVolume(Order market, Order maker, BigDecimal tradePrice) {
        BigDecimal makerQty = maker.getUnfilledQuantity();
        if (makerQty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (market.getOpen() == OrderOpen.OPEN) {
            BigDecimal makerAmt = MathUtil.calcTradeMoney(makerQty, tradePrice, maker.getMultiplier());
            BigDecimal remainingQuote = market.getVolume().subtract(market.getDealMoney());
            if (makerAmt.compareTo(remainingQuote) <= 0) {
                return makerQty;
            }
            BigDecimal vol = MathUtil.calcTradeVolume(remainingQuote, tradePrice, market.getMultiplier());
            return stripRemainder(vol);
        }

        BigDecimal remainingBase = market.getVolume().subtract(market.getDealVolume());
        return makerQty.min(remainingBase).max(BigDecimal.ZERO);
    }

    private BigDecimal stripRemainder(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal over = MathUtil.divedReminderPowByConfig(v);
        return over.compareTo(BigDecimal.ZERO) > 0 ? v.subtract(over) : v;
    }

    private Trade createTrade(Order taker, Order maker, BigDecimal price, BigDecimal volume) {
        if (volume == null || volume.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Trade t = new Trade();
        t.setTradeNonce(IdGenUtil.getGuid());
        t.setPrice(price);
        t.setVolume(volume);
        if (OrderSide.BUY.equals(taker.getSide())) {
            t.setBidId(taker.getId());
            t.setBidUserId(taker.getUid());
            t.setAskId(maker.getId());
            t.setAskUserId(maker.getUid());
            t.setBuyType(taker.getType());
            t.setSellType(maker.getType());
        } else {
            t.setBidId(maker.getId());
            t.setBidUserId(maker.getUid());
            t.setAskId(taker.getId());
            t.setAskUserId(taker.getUid());
            t.setBuyType(maker.getType());
            t.setSellType(taker.getType());
        }
        t.setTrendSide(taker.getSide());
        long now = System.currentTimeMillis();
        t.setCtime(now);
        t.setMtime(now);
        return t;
    }

    /**
     * Enforces slippage budget for market orders.
     * 
     * Calculates per-unit slippage cost based on last price and trade price, 
     * checks remaining budget, and updates used budget if trade proceeds.
     * 
     * @param taker the taker order
     * @param trade the proposed trade
     * @return true if trade can proceed within budget, false if budget exhausted
     */
    private boolean enforceAndConsumeSlippageBudget(Order taker, Trade trade) {
        if (taker == null || trade == null) {
            return true;
        }
        if (taker.getType() != OrderType.ORDER_MARKET) {
            return true;
        }
        if (taker.getSource() == OrderSource.ROBOT) {
            return true;
        }
        if (taker.getSlippageRate() == null || taker.getSlippageRate().compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        if (taker.getLastPrice() == null || taker.getLastPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        if (taker.getSlippageBudget() == null) {
            return true;
        }

        BigDecimal unitCost = perUnitSlippageCost(taker.getSide(), taker.getLastPrice(), trade.getPrice());
        if (unitCost.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }

        BigDecimal used = taker.getUsedSlippageBudget() == null ? BigDecimal.ZERO : taker.getUsedSlippageBudget();
        BigDecimal remaining = taker.getSlippageBudget().subtract(used);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal cost = unitCost.multiply(trade.getVolume()).setScale(SLIPPAGE_SCALE, RoundingMode.UP);
        if (cost.compareTo(remaining) > 0) {
            return false;
        }
        taker.setUsedSlippageBudget(used.add(cost));
        return true;
    }

    private BigDecimal perUnitSlippageCost(OrderSide side, BigDecimal lastPrice, BigDecimal tradePrice) {
        if (side == OrderSide.BUY) {
            return tradePrice.subtract(lastPrice).max(BigDecimal.ZERO);
        }
        return lastPrice.subtract(tradePrice).max(BigDecimal.ZERO);
    }
}
