# PinkWallet

## Overview

PinkWallet is a digital bank-level cryptocurrency trading platform that provides comprehensive financial services including spot trading, contract trading, and asset management.

## Core Components

### Order Matching Service

The order matching engine handles order matching logic for different order types.

**Supported Order Types:**

- **LIMIT**: Limit orders that wait for execution at a specified price
- **MARKET**: Market orders that execute immediately at the best available counterparty price
- **OTO**: Limit take profit/stop loss orders that automatically place limit or market orders to close positions when the market price reaches the trigger price

**Matching Logic:**

Limit orders follow price-time priority. When the order price crosses with the counterparty price, a trade is triggered, and the trade quantity is the minimum of both parties' unfilled quantities.

Market orders have different processing logic:
- Opening market orders: The volume field represents the total quote amount (e.g., USDT), and the tradeable quantity needs to be calculated based on the trade price and contract multiplier
- Closing market orders: The volume field represents the contract quantity, and the unfilled quantity is used directly

**Slippage Tolerance Control:**

This is PinkWallet's core feature. Slippage control only applies to market orders; limit orders do not undergo slippage checks.

The slippage algorithm workflow:
1. When an order is created, calculate the total slippage budget based on the slippage rate and latest price
2. Before each trade, calculate the unit slippage cost: for buy orders, it's the difference between the trade price and latest price; for sell orders, it's the difference between the latest price and trade price
3. If the trade price is favorable (slippage cost is 0), do not limit the trade volume
4. If the trade price is unfavorable, calculate the maximum tradeable quantity within the remaining budget
5. After the trade, update the used slippage budget

The slippage budget calculation is based on the order's slippage rate and latest price, ensuring that the execution cost of market orders stays within the user-defined tolerance range.

### Liquidation Price Calculator

Calculates liquidation prices for isolated margin mode.

**Calculation Process:**

1. Calculate position value: Position price × Position quantity × Contract multiplier
2. Calculate required margin: Position value ÷ Leverage level
3. Calculate margin difference: Held margin - Required margin
4. Calculate price gap: Margin difference ÷ (Position quantity × Contract multiplier)
5. Get liquidation factor: Based on contract name, leverage level, and position value, look up the corresponding factor system from configuration
6. Apply formula: Calculate liquidation price based on position direction (long/short)

**Liquidation Factor System:**

The system uses a multi-tiered factor configuration rather than a single fixed value. Factor selection logic:
- First, match the corresponding configuration group based on leverage level
- Then, look up the corresponding factor within the configuration group based on position value
- If the position value exceeds all configured thresholds, use the default factor
- Final factor calculation formula: `(1 / Leverage level) - Configuration factor`

For 1x leverage long positions, there is special handling logic: If the margin difference is positive, liquidation is not possible; if negative, use a dedicated 1x leverage factor for calculation.

**Liquidation Price Formula:**

- Long position: `ceil(Position price × (1 - Factor)) - Price gap`
- Short position: `ceil(Position price × (1 + Factor)) + Price gap`

All calculations use configured precision settings to ensure result accuracy.

### Trading Fee Calculator

Handles trading fee calculations.

**Fee Calculation:**

Trading fee = Trade price × Trade quantity × Contract multiplier × Fee rate

The fee rate is determined by order type and role:
- Order type: Opening or closing
- Role: Maker (provides liquidity) or Taker (consumes liquidity)

Role determination logic: If the order direction is the same as the trade trend direction, it is a Taker; otherwise, it is a Maker.

**Fee Rate Source:**

Each order carries four fee rate fields when created:
- Opening Maker rate
- Opening Taker rate
- Closing Maker rate
- Closing Taker rate

These rates are read from trading pair configuration, ensuring each order has a clear fee standard.


### Order Validator

Order validation service that ensures orders pass integrity checks before entering the matching system.

**Validation Content:**

- Order type and status validation
- Price and quantity precision validation
- Contract multiplier validation
- Minimum trade amount/quantity validation
- Fee configuration validation (for trading pairs that charge fees in quote currency)

Limit orders need to validate price and quantity precision to ensure they meet the trading pair's precision requirements. Market orders need to validate quantity precision; opening market orders validate amount precision, and closing market orders validate quantity precision.
