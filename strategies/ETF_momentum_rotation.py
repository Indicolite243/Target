"""SuperMind/Target 通用 ETF 趋势轮动策略。

这是用于核对两套回测引擎的最小策略：
1. 每 20 个交易日判断一次；
2. 使用上一交易日以前的 61 根前复权日线；
3. 沪深300ETF同时满足“收盘价高于60日均线”和“60日收益为正”时持有它；
4. 否则持有5年期国债ETF；
5. 目标仓位90%，保留约10%现金。
"""

from datetime import timedelta

from mindgo_api import *


def init(context):
    context.risk_etf = "510300.SH"
    context.defensive_etf = "511010.SH"
    context.etf_list = [context.risk_etf, context.defensive_etf]
    context.rebalance_interval = 20
    context.days_after_rebalance = context.rebalance_interval
    context.lookback = 60
    context.target_ratio = 0.90

    set_benchmark("000300.SH")
    set_commission(PerShare(type="stock", cost=0.0002))
    set_slippage(PriceSlippage(0.001))


def select_target(context):
    end_date = (get_datetime() - timedelta(days=1)).strftime("%Y-%m-%d")
    prices = get_price(
        context.risk_etf,
        end_date=end_date,
        bar_count=context.lookback + 1,
        fre_step="1d",
        fields=["close"],
        skip_paused=True,
        fq="pre",
    )
    if prices is None or len(prices) < context.lookback + 1:
        log.warning("历史行情不足，本次暂不调仓")
        return None

    close = prices["close"].astype(float)
    first_close = float(close.iloc[0])
    latest_close = float(close.iloc[-1])
    moving_average = float(close.iloc[-context.lookback:].mean())
    momentum = latest_close / first_close - 1.0 if first_close > 0 else -1.0

    if latest_close > moving_average and momentum > 0:
        return context.risk_etf
    return context.defensive_etf


def handle_bar(context, bar_dict):
    if context.days_after_rebalance < context.rebalance_interval:
        context.days_after_rebalance += 1
        return

    target = select_target(context)
    if target is None:
        return

    # 使用与已验证等权ETF策略相同的方式：先清仓非目标，再按目标金额下单。
    for code in context.etf_list:
        if code != target and code in context.portfolio.positions:
            if context.portfolio.positions[code].amount > 0:
                order_target(code, 0)

    total_value = context.portfolio.total_value
    price = bar_dict[target].close
    current_value = (
        context.portfolio.positions[target].amount * price
        if target in context.portfolio.positions else 0.0
    )
    delta_value = total_value * context.target_ratio - current_value
    if abs(delta_value) > price * 100:
        order_value(target, delta_value)

    context.days_after_rebalance = 0
    log.info("ETF趋势轮动调仓，目标标的: {}".format(target))
