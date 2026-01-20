package com.pinkwallet.foced.liquidation;

import com.pinkwallet.common.exception.WarnException;
import com.pinkwallet.contract.entity.ContractPositionDO;
import com.pinkwallet.contract.entity.ContractSymbolConfigDO;
import com.pinkwallet.foced.liquidation.config.RedisClient;
import com.pinkwallet.foced.liquidation.enums.ExplisionStatusEnum;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forced liquidation processor for positions that have reached liquidation price.
 * 
 * Orchestrates the liquidation flow: validates position state, cancels related orders,
 * selects counterparty positions for ADL (Auto-Deleveraging), and executes liquidation trades.
 * Uses Redis locks to ensure thread-safe position updates.
 */
public final class ForcedLiquidation {

    private static final Logger logger = LoggerFactory.getLogger(ForcedLiquidation.class);

    private static final int MAX_LIQUIDATION_ITERATIONS = 10;
    private static final long DEFAULT_LOCK_TTL_SECONDS = 5L;
    private static final String DEAL_POSITION_LOCK_PREFIX = "EXPLOSION_DEAL_POSITION_PREFIX_";
    private static final String SIDE_BUY = "BUY";
    private static final String SIDE_SELL = "SELL";

    private ForcedLiquidation() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Dependency interface for position and order operations.
     * Provides abstraction for position locking, order cancellation, and deal execution.
     */
    public interface Dependencies {
        ContractSymbolConfigDO symbolByContractName(String contractName);

        ContractPositionDO lockPosition(Integer positionId);

        void cancelOtoOrders(ContractSymbolConfigDO symbol, Integer positionId, String reason);

        boolean cancelAllDelegatingOrders(ContractSymbolConfigDO symbol, Long uid, Integer positionId);

        ContractPositionDO selectExplosionDealPosition(Integer contractId, String oppositeSide, int offset);

        ContractPositionDO selectExplosionDealPositionSystem(Integer contractId, String oppositeSide, int offset);

        void updateExplosionStatus(Long explosionId, Integer status);

        void executeDeal(
                ContractSymbolConfigDO symbol,
                Long explosionId,
                ContractPositionDO explosionPosition,
                ContractPositionDO dealPosition,
                AtomicInteger userOffset,
                AtomicInteger systemOffset,
                AtomicBoolean isSystem
        );
    }

    /**
     * Processes a single forced liquidation event.
     * 
     * @param deps dependency interface for position and order operations
     * @param contractName contract symbol name
     * @param explosionId liquidation event ID
     * @param positionId position ID to liquidate
     */
    public void processOneExplosion(Dependencies deps, String contractName, Long explosionId, Integer positionId) {
        Validate.notNull(deps, "Dependencies cannot be null");
        Validate.notBlank(contractName, "Contract name cannot be null or empty");
        Validate.notNull(explosionId, "Explosion ID cannot be null");
        Validate.notNull(positionId, "Position ID cannot be null");

        logger.info("Processing forced liquidation: contractName={}, explosionId={}, positionId={}",
                contractName, explosionId, positionId);

        ContractSymbolConfigDO symbol = deps.symbolByContractName(contractName);
        if (symbol == null) {
            throw new IllegalArgumentException("symbol not found: " + contractName);
        }

        ContractPositionDO explosionPos = deps.lockPosition(positionId);
        if (explosionPos == null || explosionPos.getStatus() == 0) {
            deps.updateExplosionStatus(explosionId, ExplisionStatusEnum.INVALID.getValue());
            return;
        }

        logger.debug("Cancelling OTO orders: positionId={}, uid={}", explosionPos.getId(), explosionPos.getUid());
        deps.cancelOtoOrders(symbol, explosionPos.getId(), "forced liquidation");
        if (deps.cancelAllDelegatingOrders(symbol, explosionPos.getUid(), explosionPos.getId())) {
            return;
        }

        AtomicInteger userOffset = new AtomicInteger(0);
        AtomicInteger systemOffset = new AtomicInteger(0);
        AtomicBoolean isSystem = new AtomicBoolean(true);

        int guard = 0;
        while (hasRemainingVolume(explosionPos)) {
            if (guard++ >= MAX_LIQUIDATION_ITERATIONS) {
                logger.warn("Max liquidation iterations reached: positionId={}, explosionId={}",
                        explosionPos.getId(), explosionId);
                break;
            }

            ContractPositionDO dealPos = pickDealPosition(deps, symbol, explosionPos, userOffset, systemOffset, isSystem);
            if (dealPos == null) {
                throw new WarnException("deal position not found");
            }

            String lockKey = DEAL_POSITION_LOCK_PREFIX + dealPos.getId();
            withRedisLock(lockKey, DEFAULT_LOCK_TTL_SECONDS, () ->
                    deps.executeDeal(symbol, explosionId, explosionPos, dealPos, userOffset, systemOffset, isSystem)
            );
        }

        if (!hasRemainingVolume(explosionPos)) {
            deps.updateExplosionStatus(explosionId, ExplisionStatusEnum.HAD_CLOSE_ORDERS.getValue());
        }
    }

    /**
     * Selects counterparty position for ADL liquidation.
     * Prioritizes system positions, falls back to user positions if needed.
     */
    private ContractPositionDO pickDealPosition(
            Dependencies deps,
            ContractSymbolConfigDO symbol,
            ContractPositionDO explosionPos,
            AtomicInteger userOffset,
            AtomicInteger systemOffset,
            AtomicBoolean isSystem
    ) {
        String oppositeSide = Objects.equals(explosionPos.getSide(), SIDE_BUY) ? SIDE_SELL : SIDE_BUY;

        if (isSystem.get()) {
            ContractPositionDO sys = deps.selectExplosionDealPositionSystem(explosionPos.getContractId(), oppositeSide, systemOffset.get());
            if (sys != null) {
                return sys;
            }
            return deps.selectExplosionDealPosition(explosionPos.getContractId(), oppositeSide, systemOffset.get());
        }

        ContractPositionDO user = deps.selectExplosionDealPosition(explosionPos.getContractId(), oppositeSide, userOffset.get());
        if (user != null) {
            return user;
        }
        isSystem.set(true);
        return deps.selectExplosionDealPositionSystem(explosionPos.getContractId(), oppositeSide, systemOffset.get());
    }

    /**
     * Executes action within a Redis distributed lock.
     * 
     * @param key lock key
     * @param ttlSeconds lock TTL in seconds
     * @param action code to execute under lock
     */
    private boolean hasRemainingVolume(ContractPositionDO position) {
        return position != null
                && position.getVolume() != null
                && position.getVolume().signum() > 0;
    }

    private void withRedisLock(String key, long ttlSeconds, Runnable action) {
        Validate.notBlank(key, "Lock key cannot be null or empty");
        Validate.isTrue(ttlSeconds > 0, "Lock TTL must be positive");
        Validate.notNull(action, "Action cannot be null");

        long tid = Thread.currentThread().getId();
        String lockValue = String.valueOf(tid);
        boolean locked = RedisClient.lock(key, lockValue, ttlSeconds);
        if (!locked) {
            logger.warn("Failed to acquire Redis lock: key={}", key);
            return;
        }
        try {
            action.run();
        } finally {
            RedisClient.unLock(key, lockValue);
        }
    }
}
