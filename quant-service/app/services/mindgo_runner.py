"""MindGo/SuperMind兼容回测执行器。

本文件在独立Python子进程中加载用户策略，通过运行时注入`mindgo_api`兼容模块执行日线回测。
核心职责包括行情与前复权数据对齐、开盘撮合、整手/现金/成交量限制、手续费和滑点、公司行动、
收盘估值、基准对齐、风险指标计算以及报告导出。它不连接交易Trader，也不会向QMT发送委托。
"""

import importlib.util
import json
import math
import os
import site
import sys
import types
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pandas as pd
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill

def _load_xtdata_module():
    """尽力加载xtdata；不可用时返回None，让打包行情仍可独立回测。"""

    try:
        from xtquant import xtdata as module
        return module
    except Exception:
        # 回测子进程可能使用venv，而xtquant通常安装在QMT或用户Python目录。
        candidates = []
        configured = os.environ.get("QUANT_XTQUANT_SITE_PACKAGES", "").strip()
        if configured:
            candidates.append(configured)
        user_sites = site.getusersitepackages()
        candidates.extend([user_sites] if isinstance(user_sites, str) else list(user_sites))
        candidates.append(str(Path(sys.base_prefix) / "Lib" / "site-packages"))
        for candidate in candidates:
            if candidate and Path(candidate).is_dir() and candidate not in sys.path:
                # 追加而非前置，避免QMT目录中的旧依赖覆盖当前虚拟环境。
                sys.path.append(candidate)
        try:
            import importlib
            importlib.invalidate_caches()
            return importlib.import_module("xtquant.xtdata")
        except Exception:
            # xtdata只是行情补齐能力；本地xlsx完整时不应阻止引擎启动。
            return None


# 模块加载一次并在整个回测子进程复用，避免每次读取标的重复扫描路径。
xtdata = _load_xtdata_module()


# 用户策略调用的全局兼容函数通过ENGINE转发到当前唯一回测实例。
ENGINE = None


@dataclass
class PerShare:
    """MindGo按成交金额比例计费的简化佣金配置对象。"""

    type: str = "stock"
    cost: float = 0.0
    min_trade_cost: float = 5.0


@dataclass
class PriceSlippage:
    """买卖两侧合计滑点比例配置。"""

    perc: float = 0.0


@dataclass
class Position:
    """回测持仓数量和含买入费用的移动平均成本。"""

    amount: float = 0.0
    avg_cost: float = 0.0

    @property
    def available_amount(self):
        """日线模型暂不实现T+1冻结，因此可用数量等于总数量。"""

        return self.amount

    @property
    def total_amount(self):
        """返回当前总持仓数量。"""

        return self.amount


@dataclass
class Bar:
    """策略可见OHLCV及用于真实撮合/估值的未复权价格。"""

    open: float
    high: float
    low: float
    close: float
    volume: float
    raw_open: float
    raw_high: float
    raw_low: float
    raw_close: float


class StrategyLogger:
    """提供MindGo策略常用的log.info/warning/error兼容接口。"""

    def info(self, message):
        """输出普通策略日志。"""

        print(f"[INFO] {message}")

    def warning(self, message):
        """输出策略告警日志。"""

        print(f"[WARN] {message}")

    def error(self, message):
        """输出策略错误日志；记录日志本身不会中止回测。"""

        print(f"[ERROR] {message}")


class Portfolio:
    """持有现金与证券头寸，并按引擎当前标记价动态计算总资产。"""

    def __init__(self, engine):
        """从环境变量读取初始资金，并创建空持仓容器。"""

        self._engine = engine
        self.starting_cash = float(os.environ.get("BACKTEST_INITIAL_CASH", "1000000"))
        self.cash = self.starting_cash
        self.positions = {}

    @property
    def market_value(self):
        """按当前阶段标记价汇总全部持仓市值。"""

        total = 0.0
        for code, position in self.positions.items():
            total += position.amount * self._engine.current_mark_prices.get(code, 0.0)
        return float(total)

    @property
    def total_value(self):
        """组合总资产等于可用现金加当前持仓市值。"""

        return float(self.cash + self.market_value)


class MindgoBacktestEngine:
    """
    MindGo/SuperMind 风格的轻量回测执行器。

    每个交易日按“准备行情 -> before_trading -> handle_bar -> 收盘估值 ->
    after_trading”的顺序推进。策略只通过兼容 API 发出目标仓位或目标金额，
    引擎负责整手约束、现金约束、成交量限制、滑点、手续费和持仓更新。
    """

    def __init__(self, strategy_path):
        """读取本次回测参数，并初始化撮合、行情、持仓、记录与缓存状态。"""

        # strategy_path只用于审计和动态加载；行情工作目录由父进程单独注入。
        self.strategy_path = Path(strategy_path).resolve()
        self.data_dir = Path(os.environ.get("BACKTEST_DATA_DIR", Path.cwd())).resolve()
        self.start_date = pd.Timestamp(os.environ.get("BACKTEST_START_DATE", "2020-01-01"))
        self.end_date = pd.Timestamp(os.environ.get("BACKTEST_END_DATE", "2025-01-01"))
        self.benchmark_symbol = os.environ.get("BACKTEST_BENCHMARK", "000300.SH")
        self.log = StrategyLogger()
        # 策略可在init中覆盖佣金、滑点和成交量限制；这里保存安全默认值。
        self.commission_rate = 0.0
        self.min_trade_cost = 5.0
        self.slippage_perc = 0.0
        self.volume_limit_ratio = float(os.environ.get("BACKTEST_VOLUME_LIMIT_RATIO", "0.25"))
        self.minute_volume_limit_ratio = float(os.environ.get("BACKTEST_MINUTE_VOLUME_LIMIT_RATIO", "0.5"))
        self.lot_size = max(1, int(os.environ.get("BACKTEST_LOT_SIZE", "100")))
        # SuperMind 使用回测区间十年期国债收益率均值；这里通过配置注入，当前样本约 1.95%。
        self.risk_free_rate = float(os.environ.get("BACKTEST_RISK_FREE_RATE", "0.0195"))
        # current_*字段代表当前回测日/阶段，兼容API只从这里读取，不接触未来行情。
        self.current_dt = self.start_date.to_pydatetime()
        self.current_date = self.start_date
        self.current_phase = "init"
        self.current_bars = {}
        self.current_exec_prices = {}
        self.current_mark_prices = {}
        self.current_remaining_volume = {}
        # records保存每日净值/策略自定义指标，trade_records保存模拟成交明细。
        self.records = {}
        self.trade_records = []
        # 三类缓存分别保存行情、基准行情和公司行动，避免日循环重复读取磁盘/xtdata。
        self.data_cache = {}
        self.benchmark_cache = {}
        self.corporate_actions_cache = {}
        self.applied_corporate_actions = set()
        self.artifacts = {}
        # context模拟MindGo传给策略的可扩展上下文，策略可以自由添加属性。
        self.context = SimpleNamespace()
        self.context.portfolio = Portfolio(self)
        self.context.run_params = {
            "start_date": self.start_date.strftime("%Y-%m-%d"),
            "end_date": self.end_date.strftime("%Y-%m-%d"),
        }

    def _load_single_symbol(self, code):
        """读取单只本地xlsx，构建原始/前复权价格列，并按需用xtdata补齐区间。"""

        if code in self.data_cache:
            # 同一标的只解析一次Excel；返回同一只读语义DataFrame供日循环切片。
            return self.data_cache[code]

        file_path = self.data_dir / f"{code}.xlsx"
        if not file_path.exists():
            raise FileNotFoundError(f"未找到行情文件: {file_path}")

        # 显式使用openpyxl保证xlsx解析器一致，并清理Excel表头两侧空格。
        df = pd.read_excel(file_path, engine="openpyxl")
        df.columns = [str(col).strip() for col in df.columns]
        if "time" not in df.columns:
            raise ValueError(f"{file_path.name} 缺少 time 列")

        # 无法解析的日期行删除；排序并设为索引后才能安全按回测日期切片。
        df["time"] = pd.to_datetime(df["time"], errors="coerce")
        df = df.dropna(subset=["time"]).sort_values("time").set_index("time")

        # Excel可能把数值保存为文本，统一转数值；非法单元格成为NaN后再处理。
        for col in ["open", "high", "low", "close", "volume", "adjustment_nv"]:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce")

        for col in ["open", "high", "low", "close"]:
            df[col] = df.get(col, np.nan).ffill()
        df["volume"] = df.get("volume", 0).fillna(0.0)

        if "adjustment_nv" in df.columns:
            # adjustment_nv作为前复权收盘净值，除以原始收盘得到同日复权比例。
            df["adjustment_nv"] = df["adjustment_nv"].ffill()
            valid_close = df["close"].replace(0, np.nan)
            pre_ratio = df["adjustment_nv"] / valid_close
            pre_ratio = pre_ratio.replace([np.inf, -np.inf], np.nan).ffill().fillna(1.0)
            df["open_pre"] = df["open"] * pre_ratio
            df["high_pre"] = df["high"] * pre_ratio
            df["low_pre"] = df["low"] * pre_ratio
            df["close_pre"] = df["adjustment_nv"]
        else:
            # 没有复权字段时，策略查询和真实成交均使用原始OHLC。
            df["open_pre"] = df["open"]
            df["high_pre"] = df["high"]
            df["low_pre"] = df["low"]
            df["close_pre"] = df["close"]

        df = self._extend_with_xtdata(code, df)

        self.data_cache[code] = df
        return df

    def _extend_with_xtdata(self, code, frame):
        """当本地行情未覆盖回测区间时合并xtdata数据，并校准复权序列尺度。"""

        if xtdata is None or frame.empty:
            return frame
        need_before = self.start_date < frame.index.min()
        need_after = self.end_date > frame.index.max()
        if not need_before and not need_after:
            return frame
        try:
            downloaded = self._download_xtdata_frame(code)
        except Exception as exc:
            self.log.warning(f"{code} 行情自动补齐失败: {exc}")
            return frame
        if downloaded is None or downloaded.empty:
            return frame
        # 两个数据源的前复权绝对尺度可能不同，利用最后一个重叠日计算缩放因子。
        common_dates = frame.index.intersection(downloaded.index)
        if len(common_dates) > 0 and "close_pre" in frame.columns and "close_pre" in downloaded.columns:
            overlap_date = common_dates[-1]
            local_pre = float(frame.at[overlap_date, "close_pre"])
            downloaded_pre = float(downloaded.at[overlap_date, "close_pre"])
            if local_pre > 0 and downloaded_pre > 0:
                pre_scale = local_pre / downloaded_pre
                for col in ["open_pre", "high_pre", "low_pre", "close_pre"]:
                    downloaded[col] = downloaded[col] * pre_scale
        # 本地打包行情优先，重复日期保留first；xtdata只负责补缺口。
        combined = pd.concat([frame, downloaded], axis=0)
        combined = combined[~combined.index.duplicated(keep="first")].sort_index()
        for col in ["open", "high", "low", "close"]:
            combined[col] = pd.to_numeric(combined[col], errors="coerce").ffill()
        combined["volume"] = pd.to_numeric(combined.get("volume", 0), errors="coerce").fillna(0.0)
        for col in ["open_pre", "high_pre", "low_pre", "close_pre"]:
            if col not in combined.columns:
                combined[col] = combined[col.replace("_pre", "")]
            combined[col] = combined[col].fillna(combined[col.replace("_pre", "")])
        return combined

    def _download_xtdata_frame(self, code):
        """下载原始和前复权日线，整理成引擎统一的九列行情表。"""

        if xtdata is None:
            return None
        # 向前多取约一年，为动量指标和首日基准价格提供预热数据。
        start_time = (self.start_date - pd.Timedelta(days=370)).strftime("%Y%m%d")
        end_time = self.end_date.strftime("%Y%m%d")
        xtdata.download_history_data(code, period="1d", start_time=start_time, end_time=end_time)
        # 原始未复权价用于模拟实际成交和收盘估值。
        market = xtdata.get_market_data_ex(
            stock_list=[code],
            period="1d",
            start_time=start_time,
            end_time=end_time,
            count=-1,
            dividend_type="none",
            fill_data=True,
        )
        df = market.get(code) if isinstance(market, dict) else None
        if df is None or df.empty:
            return None
        df = df.copy()
        # 新旧xtdata可能把日期放在索引或毫秒time列，依次尝试解析。
        parsed_index = pd.to_datetime(df.index.astype(str), format="%Y%m%d", errors="coerce")
        if parsed_index.isna().all() and "time" in df.columns:
            parsed_index = pd.to_datetime(df["time"], unit="ms", errors="coerce").dt.floor("D")
        df.index = parsed_index
        df = df[~df.index.isna()].sort_index()
        for col in ["open", "high", "low", "close", "volume"]:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce")
        # 前复权序列只供策略信号查询，不能拿来作为真实成交价格。
        adjusted_market = xtdata.get_market_data_ex(
            stock_list=[code],
            period="1d",
            start_time=start_time,
            end_time=end_time,
            count=-1,
            dividend_type="front",
            fill_data=True,
        )
        adjusted = adjusted_market.get(code) if isinstance(adjusted_market, dict) else None
        if adjusted is not None and not adjusted.empty:
            adjusted = adjusted.copy()
            adjusted.index = pd.to_datetime(adjusted.index.astype(str), format="%Y%m%d", errors="coerce")
            adjusted = adjusted[~adjusted.index.isna()].sort_index()
        for col in ["open", "high", "low", "close"]:
            if adjusted is not None and col in adjusted.columns:
                df[f"{col}_pre"] = pd.to_numeric(adjusted[col], errors="coerce").reindex(df.index)
            else:
                df[f"{col}_pre"] = df[col]
            df[f"{col}_pre"] = df[f"{col}_pre"].fillna(df[col])
        return df[["open", "high", "low", "close", "volume", "open_pre", "high_pre", "low_pre", "close_pre"]]

    def _load_xtdata_symbol(self, code):
        """专用于缺少本地文件的标的/基准，读取并缓存xtdata行情。"""

        if code in self.benchmark_cache:
            return self.benchmark_cache[code]
        if xtdata is None:
            raise FileNotFoundError(f"未找到行情文件，且 xtdata 不可用: {code}")

        df = self._download_xtdata_frame(code)
        if df is None or df.empty:
            raise FileNotFoundError(f"无法从 xtdata 获取基准数据: {code}")

        self.benchmark_cache[code] = df
        return df

    def _load_benchmark_symbol(self, code):
        """基准优先使用同目录打包文件，不存在时再访问xtdata。"""

        file_path = self.data_dir / f"{code}.xlsx"
        if file_path.exists():
            return self._load_single_symbol(code)
        return self._load_xtdata_symbol(code)

    def get_price(self, code, start_date=None, end_date=None, bar_count=None, fre_step="1d", fields=None, skip_paused=True, fq=None):
        """实现MindGo历史行情API，并保证策略只能按传入截止日读取数据。"""

        # 当前内核只支持日线且已用填充数据处理停牌，因此兼容参数不再向下传递。
        del fre_step, skip_paused
        df = self._load_single_symbol(code)
        if end_date:
            df = df.loc[df.index <= pd.Timestamp(end_date)]
        if start_date:
            df = df.loc[df.index >= pd.Timestamp(start_date)]
        if bar_count:
            df = df.tail(int(bar_count))

        price_df = pd.DataFrame(index=df.index)
        requested_fields = fields or ["open", "high", "low", "close", "volume"]
        if fq == "pre" and not df.empty and "close_pre" in df.columns:
            # 动态锚定到查询窗口最后一天原始收盘价，避免使用回测终点造成未来函数。
            anchor = df.iloc[-1]
            anchor_adjusted = float(anchor.get("close_pre", 0.0) or 0.0)
            anchor_close = float(anchor.get("close", 0.0) or 0.0)
            anchor_scale = anchor_close / anchor_adjusted if anchor_adjusted > 0 and anchor_close > 0 else 1.0
            for field in requested_fields:
                if field == "volume":
                    price_df[field] = df["volume"]
                elif f"{field}_pre" in df.columns:
                    price_df[field] = df[f"{field}_pre"] * anchor_scale
        else:
            for field in requested_fields:
                if field in df.columns:
                    price_df[field] = df[field]
        return price_df.copy()

    def set_commission(self, commission):
        """应用策略配置的按金额佣金率和最低单笔手续费。"""

        # 策略通过PerShare/相关对象注入费率；最低手续费单独保留，避免小额交易低估成本。
        self.commission_rate = float(getattr(commission, "cost", 0.0))
        self.min_trade_cost = max(0.0, float(getattr(commission, "min_trade_cost", 5.0)))

    def set_slippage(self, slippage):
        """设置买卖两侧合计的价格滑点比例。"""

        # 滑点按总比例的一半分摊到买入和卖出：买入价上浮，卖出价下浮。
        self.slippage_perc = float(getattr(slippage, "perc", 0.0))

    def set_volume_limit(self, daily_ratio=0.25, minute_ratio=0.5):
        """设置日成交量参与上限；分钟比例保留作MindGo接口兼容。"""

        self.volume_limit_ratio = max(0.0, float(daily_ratio))
        self.minute_volume_limit_ratio = max(0.0, float(minute_ratio))

    def set_benchmark(self, benchmark_symbol):
        """设置绩效比较基准，空值不覆盖当前配置。"""

        if benchmark_symbol:
            self.benchmark_symbol = str(benchmark_symbol)

    def _get_exec_price(self, code, is_buy):
        """根据未复权开盘价和双边滑点计算本次模拟成交价。"""

        # 执行价优先使用当日开盘价；缺失时退化到当前标记价，保证异常行情不会凭空成交。
        raw_price = float(self.current_exec_prices.get(code, 0.0))
        if raw_price <= 0:
            raw_price = float(self.current_mark_prices.get(code, 0.0))
        if raw_price <= 0:
            return 0.0
        half_slippage = self.slippage_perc / 2.0
        return raw_price * (1.0 + half_slippage if is_buy else 1.0 - half_slippage)

    def _consume_trade_volume(self, code, requested_qty):
        """按整手向下取整，并消耗该证券当日剩余可成交数量。"""

        # 单日可成交量按“行情成交量 × 限制比例”计算，并向下取整到整手。
        # 同一标的当天多次下单会消耗同一个 remaining 额度，避免策略拆单绕过成交量上限。
        lot = float(self.lot_size)
        requested_qty = math.floor(max(0.0, float(requested_qty)) / lot) * lot
        remaining = float(self.current_remaining_volume.get(code, 0.0))
        actual_qty = min(requested_qty, math.floor(remaining / lot) * lot)
        self.current_remaining_volume[code] = max(0.0, remaining - actual_qty)
        return actual_qty

    def _trade_fee(self, trade_value):
        """返回比例佣金与最低手续费中的较大值。"""

        # 手续费取按比例计算值和最低手续费中的较大值。
        if trade_value <= 0:
            return 0.0
        return max(trade_value * self.commission_rate, self.min_trade_cost)

    def _append_trade(self, code, action, quantity, exec_price, fee):
        """把一次模拟成交写入内存明细，供指标与报告导出。"""

        self.trade_records.append({
            "date": self.current_date.strftime("%Y-%m-%d"),
            "etf": code,
            "action": action,
            "shares": quantity,
            "price": exec_price,
            "amount": quantity * exec_price,
            "commission": fee,
        })

    def order_value(self, code, delta_value):
        """
        按金额交易，是所有订单 API 的底层撮合入口。

        买入：先受现金上限约束，再受整手和成交量上限约束，最后更新现金和持仓成本。
        卖出：先受当前可用持仓约束，再扣除手续费并回收现金。
        返回值只是模拟成交对象，不是 QMT 委托；回测没有外部券商异步状态。
        """
        if code not in self.current_bars:
            # 当日无有效Bar的证券不能成交，避免用历史残留价格虚构交易。
            return None

        delta_value = float(delta_value)
        if abs(delta_value) <= 1e-12:
            return None

        is_buy = delta_value > 0
        exec_price = self._get_exec_price(code, is_buy)
        if exec_price <= 0:
            return None

        position = self.context.portfolio.positions.setdefault(code, Position())
        action = "买入" if is_buy else "卖出"

        if is_buy:
            # 先扣除可能产生的最低手续费，得到理论最大可买金额；之后还会再次按整手复核。
            max_notional = max(0.0, self.context.portfolio.cash - self.min_trade_cost) / (1.0 + self.commission_rate)
            target_notional = min(delta_value, max_notional)
            if target_notional <= 0:
                return None
            requested_qty = target_notional / exec_price
            quantity = self._consume_trade_volume(code, requested_qty)
            if quantity <= 0:
                return None
            trade_value = quantity * exec_price
            fee = self._trade_fee(trade_value)
            total_cost = trade_value + fee
            if total_cost > self.context.portfolio.cash:
                # 最低手续费会使理论数量仍超出现金，按整手重新计算真正可负担数量。
                affordable = math.floor(
                    max(0.0, self.context.portfolio.cash - self.min_trade_cost)
                    / (exec_price * (1.0 + self.commission_rate))
                    / self.lot_size
                ) * self.lot_size
                quantity = min(quantity, affordable)
                if quantity <= 0:
                    return None
                trade_value = quantity * exec_price
                fee = self._trade_fee(trade_value)
                total_cost = trade_value + fee
            # 买入费用计入持仓成本，移动平均后再扣减现金。
            previous_cost = position.avg_cost * position.amount
            position.amount += quantity
            position.avg_cost = (previous_cost + trade_value + fee) / position.amount if position.amount > 0 else 0.0
            self.context.portfolio.cash -= total_cost
        else:
            # 回测不允许卖出不存在的持仓；实际成交量还要同时受当日剩余成交量限制。
            available_amount = max(position.amount, 0.0)
            if available_amount <= 0:
                return None
            requested_qty = min(abs(delta_value) / exec_price, available_amount)
            quantity = self._consume_trade_volume(code, requested_qty)
            quantity = min(quantity, available_amount)
            if quantity <= 0:
                return None
            trade_value = quantity * exec_price
            fee = self._trade_fee(trade_value)
            # 卖出手续费从回收现金中扣减；剩余持仓继续保留原平均成本。
            self.context.portfolio.cash += trade_value - fee
            position.amount -= quantity
            if position.amount <= 1e-12:
                self.context.portfolio.positions.pop(code, None)
            else:
                position.avg_cost = position.avg_cost

        self._append_trade(code, action, quantity, exec_price, fee)
        return SimpleNamespace(code=code, action=action, amount=quantity, price=exec_price)

    def order_target(self, code, target_amount):
        """把目标持仓数量转换为增减金额，并复用统一撮合入口。"""

        # 目标数量 API 先转换成数量差，再复用 order_value，保证所有费用和限制只有一套实现。
        position = self.context.portfolio.positions.get(code)
        current_amount = position.amount if position else 0.0
        delta_amount = float(target_amount) - current_amount
        reference_price = float(self.current_mark_prices.get(code, 0.0))
        return self.order_value(code, delta_amount * reference_price)

    def order_target_value(self, code, target_value):
        """按当前持仓市值与目标市值之差发起模拟交易。"""

        # 目标市值 API 根据当前标记价计算现有市值，再把差额交给金额交易逻辑。
        current_value = 0.0
        if code in self.context.portfolio.positions:
            current_value = self.context.portfolio.positions[code].amount * float(self.current_mark_prices.get(code, 0.0))
        return self.order_value(code, float(target_value) - current_value)

    def order_target_percent(self, code, target_percent):
        """把组合资产目标比例转换为目标市值。"""

        # 组合总资产 × 目标比例得到目标市值；现金、滑点、整手等约束仍在底层处理。
        target_value = self.context.portfolio.total_value * float(target_percent)
        return self.order_target_value(code, target_value)

    def order_percent(self, code, percent):
        """按组合当前总资产的一定比例增减单只证券市值。"""

        delta_value = self.context.portfolio.total_value * float(percent)
        return self.order_value(code, delta_value)

    def record(self, **kwargs):
        """记录策略在当前交易日主动上报的净值或自定义字段。"""

        day_key = self.current_date.strftime("%Y-%m-%d")
        self.records.setdefault(day_key, {})
        self.records[day_key].update(kwargs)

    def build_calendar(self, symbols):
        """取全部交易标的行情日期交集，并裁剪到回测区间。"""

        # 使用交集保证handle_bar收到的每只标的在当天都有数据，避免部分缺失导致策略偏差。
        frames = [self._load_single_symbol(code) for code in symbols]
        calendar = frames[0].index
        for frame in frames[1:]:
            calendar = calendar.intersection(frame.index)
        calendar = calendar[(calendar >= self.start_date) & (calendar <= self.end_date)]
        return list(calendar)

    def build_bars(self, symbols, current_date):
        """为指定交易日构造全部标的Bar字典。"""

        bar_dict = {}
        for code in symbols:
            frame = self._load_single_symbol(code)
            row = frame.loc[current_date]
            # 策略可见字段与raw字段当前都取未复权价；前复权只通过get_price显式请求。
            bar_dict[code] = Bar(
                open=float(row["open"]),
                high=float(row["high"]),
                low=float(row["low"]),
                close=float(row["close"]),
                volume=float(row["volume"]),
                raw_open=float(row["open"]),
                raw_high=float(row["high"]),
                raw_low=float(row["low"]),
                raw_close=float(row["close"]),
            )
        return bar_dict

    def _load_corporate_actions(self, code):
        """从xtdata读取并缓存分红、送转和配股事件。"""

        if code in self.corporate_actions_cache:
            return self.corporate_actions_cache[code]
        actions = {}
        if xtdata is not None:
            try:
                # 事件按除权日期YYYYMMDD索引，字段统一转float便于撮合计算。
                frame = xtdata.get_divid_factors(code)
                if frame is not None and not frame.empty:
                    for index, row in frame.iterrows():
                        digits = "".join(ch for ch in str(index) if ch.isdigit())[:8]
                        if len(digits) != 8:
                            # 索引格式未知时回退到行内毫秒time字段。
                            timestamp = pd.to_datetime(row.get("time"), unit="ms", errors="coerce")
                            digits = "" if pd.isna(timestamp) else timestamp.strftime("%Y%m%d")
                        if len(digits) == 8:
                            actions[digits] = {
                                "interest": float(row.get("interest", 0.0) or 0.0),
                                "stock_bonus": float(row.get("stockBonus", 0.0) or 0.0),
                                "stock_gift": float(row.get("stockGift", 0.0) or 0.0),
                                "allot_num": float(row.get("allotNum", 0.0) or 0.0),
                                "allot_price": float(row.get("allotPrice", 0.0) or 0.0),
                            }
            except Exception as exc:
                self.log.warning(f"{code} 除权除息数据读取失败: {exc}")
        self.corporate_actions_cache[code] = actions
        return actions

    def _apply_corporate_actions(self, current_date, symbols):
        """
        在当日策略执行前应用公司行动。

        分红增加现金并降低成本；送股/转增改变数量与成本；配股只有在现金足够且
        仍有持仓时才执行。applied_corporate_actions 防止同一交易日重复应用事件。
        """
        date_key = pd.Timestamp(current_date).strftime("%Y%m%d")
        for code in symbols:
            # code+日期形成幂等键，防止同一天状态准备被重复调用时重复送股或分红。
            action_key = (code, date_key)
            if action_key in self.applied_corporate_actions:
                continue
            self.applied_corporate_actions.add(action_key)
            position = self.context.portfolio.positions.get(code)
            if position is None or position.amount <= 0:
                continue
            action = self._load_corporate_actions(code).get(date_key)
            if not action:
                continue
            old_amount = float(position.amount)
            interest = max(0.0, action["interest"])
            if interest > 0:
                # 每股现金分红增加现金，并等额降低单位持仓成本。
                self.context.portfolio.cash += old_amount * interest
                position.avg_cost = max(0.0, position.avg_cost - interest)
            share_factor = 1.0 + action["stock_bonus"] + action["stock_gift"]
            if share_factor > 0 and abs(share_factor - 1.0) > 1e-12:
                # 送转股增加数量但不增加总成本，因此单位成本按比例摊薄。
                position.amount = math.floor(old_amount * share_factor)
                position.avg_cost = position.avg_cost / share_factor
            allot_num = max(0.0, action["allot_num"])
            allot_price = max(0.0, action["allot_price"])
            if allot_num > 0 and allot_price > 0:
                # 配股受现金约束；实际认购数不能超过理论获配数和可负担数。
                allot_shares = math.floor(old_amount * allot_num)
                affordable = math.floor(self.context.portfolio.cash / allot_price)
                allot_shares = min(allot_shares, affordable)
                if allot_shares > 0:
                    previous_cost = position.avg_cost * position.amount
                    position.amount += allot_shares
                    self.context.portfolio.cash -= allot_shares * allot_price
                    position.avg_cost = (previous_cost + allot_shares * allot_price) / position.amount

    def _prepare_daily_state(self, current_date, symbols):
        """准备当日公司行动、Bar、开盘执行价、初始标记价和成交量额度。"""

        self.current_date = pd.Timestamp(current_date)
        # 公司行动必须先于策略和当日成交，否则数量、现金和成本都会错位。
        self._apply_corporate_actions(self.current_date, symbols)
        self.current_bars = self.build_bars(symbols, self.current_date)
        self.current_exec_prices = {
            code: (bar.raw_open if bar.raw_open > 0 else bar.raw_close)
            for code, bar in self.current_bars.items()
        }
        self.current_mark_prices = {
            code: (bar.raw_open if bar.raw_open > 0 else bar.raw_close)
            for code, bar in self.current_bars.items()
        }
        # 每日可成交量按行情成交量×参与率向下取整到整手，多次下单共用该余额。
        self.current_remaining_volume = {
            code: math.floor(max(0.0, bar.volume * self.volume_limit_ratio) / self.lot_size) * self.lot_size
            for code, bar in self.current_bars.items()
        }

    def _mark_to_close(self):
        """将组合标记价从开盘执行价切换为收盘价，用于日终净值。"""

        # 策略在handle_bar中按开盘价成交，日终净值改用收盘价估值。
        self.current_mark_prices = {
            code: (bar.raw_close if bar.raw_close > 0 else bar.raw_open)
            for code, bar in self.current_bars.items()
        }

    def _set_phase_time(self, phase):
        """设置MindGo生命周期阶段及其约定时间，并同步到策略context。"""

        # 固定阶段时间让策略get_datetime得到稳定、可复现的时点。
        if phase == "before_trading":
            dt = self.current_date.replace(hour=9, minute=0, second=0).to_pydatetime()
        elif phase == "handle_bar":
            dt = self.current_date.replace(hour=9, minute=31, second=0).to_pydatetime()
        elif phase == "after_trading":
            dt = self.current_date.replace(hour=15, minute=30, second=0).to_pydatetime()
        else:
            dt = self.current_date.to_pydatetime()
        self.current_phase = phase
        self.current_dt = dt
        self.context.current_dt = dt
        self.context.current_phase = phase

    def _get_series_value_on_or_before(self, series, current_date):
        """读取目标日或之前最近值；区间开头无历史时才使用之后第一值。"""

        current_ts = pd.Timestamp(current_date)
        eligible = series.loc[series.index <= current_ts]
        if len(eligible) == 0:
            eligible = series.loc[series.index >= current_ts]
        if len(eligible) == 0:
            raise KeyError(f"找不到 {current_ts.strftime('%Y-%m-%d')} 对应的行情数据")
        return float(eligible.iloc[-1] if eligible.index[-1] <= current_ts else eligible.iloc[0])

    def export_results(self):
        """
        把引擎内部记录转换为前端统一结果协议。

        这里同时生成策略净值、基准净值、超额收益、日收益、成交量和风险指标；
        因此前端不需要理解撮合细节，只消费结构化结果。
        """
        if not self.records:
            raise RuntimeError("策略运行结束后没有记录任何净值数据")

        # 记录按日期排序，所有后续策略、基准和图表数组共享该顺序。
        ordered_dates = sorted(self.records.keys())
        start_cash = self.context.portfolio.starting_cash
        benchmark_df = self._load_benchmark_symbol(self.benchmark_symbol)

        first_trade_date = pd.Timestamp(ordered_dates[0])
        benchmark_close_series = pd.to_numeric(benchmark_df["close"], errors="coerce").dropna()
        # 基准起点优先使用首个策略交易日前一收盘，体现首日从开盘到收盘的基准变化。
        benchmark_history = benchmark_close_series.loc[benchmark_close_series.index < first_trade_date]
        benchmark_base = float(benchmark_history.iloc[-1]) if len(benchmark_history) > 0 else None

        strategy_nav = []
        benchmark_nav = []

        for date_str in ordered_dates:
            # 策略净值除以初始资金；缺少主动record时使用引擎日终总资产。
            record = self.records[date_str]
            net_value = float(record.get("net_value", start_cash))
            strategy_nav.append(net_value / start_cash)

            bench_close = self._get_series_value_on_or_before(benchmark_close_series, date_str)
            if benchmark_base is None:
                benchmark_base = bench_close
            benchmark_nav.append(bench_close / benchmark_base)

        strategy_nav = np.asarray(strategy_nav, dtype=float)
        benchmark_nav = np.asarray(benchmark_nav, dtype=float)
        strategy_returns = np.round((strategy_nav - 1.0) * 100.0, 2).tolist()
        benchmark_returns = np.round((benchmark_nav - 1.0) * 100.0, 2).tolist()
        # SuperMind 的超额收益采用复合净值比，而不是策略收益率减基准收益率。
        excess_returns = np.round((strategy_nav / benchmark_nav - 1.0) * 100.0, 2).tolist()

        # 首个交易日同样存在从初始资金到收盘净值的一日收益，必须显式补入 1.0。
        nv_series = np.concatenate(([1.0], strategy_nav))
        peak = np.maximum.accumulate(nv_series)
        drawdown = (nv_series - peak) / peak
        max_drawdown = abs(float(np.min(drawdown))) * 100 if len(drawdown) else 0.0

        # 策略和基准都从显式初始净值1.0计算日收益，随后按位置对齐。
        daily_returns = pd.Series(nv_series).pct_change().dropna().reset_index(drop=True)
        benchmark_daily_returns = pd.Series(
            np.concatenate(([1.0], benchmark_nav))
        ).pct_change().dropna().reset_index(drop=True)
        paired = pd.concat([daily_returns, benchmark_daily_returns], axis=1, join="inner")
        paired.columns = ["strategy", "benchmark"]

        n_daily = len(daily_returns)
        if n_daily > 0:
            daily_mean = float(daily_returns.mean())
            volatility = float(np.sqrt((250.0 / n_daily) * np.square(daily_returns - daily_mean).sum()))
            win_rate = float((daily_returns > 0).mean())
        else:
            volatility = 0.0
            win_rate = 0.0

        if len(paired) > 1:
            # Beta采用策略/基准离均差协方差比方差，避免受绝对收益水平影响。
            benchmark_centered = paired["benchmark"] - paired["benchmark"].mean()
            strategy_centered = paired["strategy"] - paired["strategy"].mean()
            benchmark_sum_squares = float(np.square(benchmark_centered).sum())
        else:
            benchmark_sum_squares = 0.0
        if benchmark_sum_squares > 0:
            beta = float((strategy_centered * benchmark_centered).sum() / benchmark_sum_squares)
            active_return = paired["strategy"] - paired["benchmark"]
        else:
            beta = 0.0
            active_return = pd.Series(dtype=float)

        # 累计收益直接取最终净值；年化收益按250交易日几何复合。
        total_return_decimal = float(strategy_nav[-1] - 1.0)
        benchmark_total_return_decimal = float(benchmark_nav[-1] - 1.0)
        annual_return_decimal = float(strategy_nav[-1] ** (250.0 / n_daily) - 1.0) if n_daily else 0.0
        benchmark_annual_return_decimal = float(benchmark_nav[-1] ** (250.0 / n_daily) - 1.0) if n_daily else 0.0
        total_return = total_return_decimal * 100.0
        benchmark_total_return = benchmark_total_return_decimal * 100.0
        annual_return = annual_return_decimal * 100.0
        benchmark_annual_return = benchmark_annual_return_decimal * 100.0

        risk_free_rate = self.risk_free_rate

        # Alpha使用CAPM年化口径；Sharpe以年化波动率为风险分母。
        alpha = annual_return_decimal - risk_free_rate - beta * (benchmark_annual_return_decimal - risk_free_rate)
        sharpe = (annual_return_decimal - risk_free_rate) / volatility if volatility > 0 else 0.0

        if len(active_return) > 1:
            # 跟踪误差是主动日收益的样本标准差年化。
            active_mean = float(active_return.mean())
            tracking_error = float(np.sqrt((250.0 / (len(active_return) - 1)) * np.square(active_return - active_mean).sum()))
            relative_win_rate = float((active_return > 0).mean())
        else:
            tracking_error = 0.0
            relative_win_rate = 0.0

        # SuperMind 页面实际使用年化日主动收益均值；这与文档中的几何年化文字表述略有差异。
        annual_active_return = float(active_return.mean() * 250.0) if len(active_return) else 0.0
        information_ratio = annual_active_return / tracking_error if tracking_error > 0 else 0.0

        if len(paired) > 0:
            # 仅在策略跑输基准的日期计入下行差值，与当前SuperMind展示口径保持一致。
            downside_returns = np.where(
                paired["strategy"].to_numpy(dtype=float) < paired["benchmark"].to_numpy(dtype=float),
                paired["strategy"].to_numpy(dtype=float) - paired["benchmark"].to_numpy(dtype=float),
                0.0,
            )
            downside_risk = float(np.sqrt((250.0 / len(paired)) * np.square(downside_returns).sum()))
        else:
            downside_risk = 0.0

        sortino = (annual_return_decimal - risk_free_rate) / downside_risk if downside_risk > 0 else 0.0
        # 成交统计从唯一trade_records来源汇总，避免报告与净值使用不同订单集合。
        total_commission = sum(float(trade.get("commission", 0.0)) for trade in self.trade_records)
        buy_trades = sum(1 for trade in self.trade_records if trade.get("action") == "买入")
        sell_trades = sum(1 for trade in self.trade_records if trade.get("action") == "卖出")
        total_turnover = sum(float(trade.get("amount", 0.0)) for trade in self.trade_records)
        turnover_ratio = (total_turnover / start_cash) * 100 if start_cash else 0.0
        final_net_value = float(self.context.portfolio.total_value)
        metrics = {
            "total_return": f"{total_return:.2f}%",
            "benchmark_return": f"{benchmark_total_return:.2f}%",
            "annual_return": f"{annual_return:.2f}%",
            "benchmark_annual_return": f"{benchmark_annual_return:.2f}%",
            "max_drawdown": f"{max_drawdown:.2f}%",
            "sharpe_ratio": f"{sharpe:.2f}",
            "sortino_ratio": f"{sortino:.2f}",
            "alpha": f"{alpha:.2f}",
            "beta": f"{beta:.2f}",
            "volatility": f"{volatility:.2f}",
            "tracking_error": f"{tracking_error:.2f}",
            "information_ratio": f"{information_ratio:.2f}",
            "downside_risk": f"{downside_risk:.2f}",
            "win_rate": f"{win_rate * 100:.2f}%",
            "relative_win_rate": f"{relative_win_rate * 100:.2f}%",
            "final_net_value": f"{final_net_value:,.2f}",
            "trade_count": str(len(self.trade_records)),
            "buy_trade_count": str(buy_trades),
            "sell_trade_count": str(sell_trades),
            "turnover_ratio": f"{turnover_ratio:.2f}%",
            "total_commission": f"{total_commission:,.2f}",
        }

        # 先输出人类可读报告，再把路径写入最终JSON artifacts。
        self._export_markdown()
        self._export_excel()

        json_payload = {
            "dates": ordered_dates,
            "strategy": strategy_returns,
            "benchmark": benchmark_returns,
            "excess": excess_returns,
            "_skip_normalization": True,
            "benchmark_symbol": self.benchmark_symbol,
            "engine": {
                "engine_type": "mindgo_runner",
                "slippage_perc": self.slippage_perc,
                "commission_rate": self.commission_rate,
                "volume_limit_ratio": self.volume_limit_ratio,
                "risk_free_rate": self.risk_free_rate,
            },
            "artifacts": self.artifacts,
            "metrics": metrics,
        }

        # 父进程通常指定任务专属结果路径；独立运行时回退到行情目录。
        result_json_path = os.environ.get("BACKTEST_RESULT_JSON_PATH")
        target_json_path = Path(result_json_path) if result_json_path else (self.data_dir / "strategy_performance.json")
        with open(target_json_path, "w", encoding="utf-8") as f:
            json.dump(json_payload, f, ensure_ascii=False, indent=2)
        return json_payload

    def _export_markdown(self):
        """生成包含绩效摘要和全部成交记录的Markdown报告。"""

        # 时间戳防止同一工作目录多次运行覆盖旧报告。
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        report_path = self.data_dir / f"ETF_strategy_report_{timestamp}.md"
        final_value = self.context.portfolio.total_value
        pnl = final_value - self.context.portfolio.starting_cash
        lines = [
            "# ETF策略回测报告",
            f"**生成时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
            "",
            "## 1. 绩效摘要",
            f"- **初始资金**: {self.context.portfolio.starting_cash:,.2f}",
            f"- **最终资金**: {final_value:,.2f}",
            f"- **总收益率**: {(pnl / self.context.portfolio.starting_cash) * 100:.2f}%",
            f"- **总盈亏额**: {pnl:,.2f}",
            "",
            "## 2. 交易记录",
            "| 日期 | 标的 | 操作 | 价格 | 数量 | 金额 | 佣金 |",
            "|---|---|---|---|---|---|---|",
        ]
        for trade in self.trade_records:
            lines.append(
                f"| {trade['date']} | {trade['etf']} | {trade['action']} | "
                f"{trade['price']:.4f} | {trade['shares']:.4f} | {trade['amount']:.2f} | {trade['commission']:.2f} |"
            )
        report_path.write_text("\n".join(lines), encoding="utf-8")
        self.artifacts["markdown_report"] = str(report_path)

    def _export_excel(self):
        """生成便于筛选和人工复核的Excel成交明细。"""

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        excel_path = self.data_dir / f"ETF_strategy_trades_{timestamp}.xlsx"
        wb = Workbook()
        ws = wb.active
        ws.title = "所有交易"
        headers = ["日期", "代码", "操作", "数量", "价格", "金额", "佣金"]
        ws.append(headers)
        # 仅设置轻量表头样式，不引入复杂模板以降低生成耗时。
        fill = PatternFill(start_color="CCE5FF", fill_type="solid")
        font = Font(bold=True)
        for cell in ws[1]:
            cell.fill = fill
            cell.font = font
        for trade in self.trade_records:
            ws.append([
                trade["date"],
                trade["etf"],
                trade["action"],
                trade["shares"],
                trade["price"],
                trade["amount"],
                trade["commission"],
            ])
        wb.save(excel_path)
        self.artifacts["trade_excel"] = str(excel_path)


def get_datetime():
    """兼容MindGo的当前阶段时间查询。"""

    return ENGINE.current_dt


def get_price(*args, **kwargs):
    """把策略行情请求转发给当前引擎实例。"""

    return ENGINE.get_price(*args, **kwargs)


def set_commission(*args, **kwargs):
    """把策略佣金配置转发给当前引擎。"""

    return ENGINE.set_commission(*args, **kwargs)


def set_slippage(*args, **kwargs):
    """把策略滑点配置转发给当前引擎。"""

    return ENGINE.set_slippage(*args, **kwargs)


def set_volume_limit(*args, **kwargs):
    """把策略成交量限制配置转发给当前引擎。"""

    return ENGINE.set_volume_limit(*args, **kwargs)


def set_benchmark(*args, **kwargs):
    """把策略基准设置转发给当前引擎。"""

    return ENGINE.set_benchmark(*args, **kwargs)


def order_value(*args, **kwargs):
    """兼容按增减金额下单接口；仅产生回测内模拟成交。"""

    return ENGINE.order_value(*args, **kwargs)


def order_target(*args, **kwargs):
    """兼容目标持仓数量接口。"""

    return ENGINE.order_target(*args, **kwargs)


def order_target_value(*args, **kwargs):
    """兼容目标持仓市值接口。"""

    return ENGINE.order_target_value(*args, **kwargs)


def order_target_percent(*args, **kwargs):
    """兼容目标组合占比接口。"""

    return ENGINE.order_target_percent(*args, **kwargs)


def order_percent(*args, **kwargs):
    """兼容按组合比例增减仓接口。"""

    return ENGINE.order_percent(*args, **kwargs)


def record(**kwargs):
    """兼容策略自定义日记录接口。"""

    return ENGINE.record(**kwargs)


def install_mindgo_shim():
    """把引擎方法注册成 mindgo_api 模块，使用户上传的策略无需修改 import。"""
    # 动态模块只存在于当前回测子进程，不会写入用户环境或安装第三方包。
    shim = types.ModuleType("mindgo_api")
    shim.get_datetime = get_datetime
    shim.get_price = get_price
    shim.set_commission = set_commission
    shim.set_slippage = set_slippage
    shim.set_volume_limit = set_volume_limit
    shim.set_benchmark = set_benchmark
    shim.order_value = order_value
    shim.order_target = order_target
    shim.order_target_value = order_target_value
    shim.order_target_percent = order_target_percent
    shim.order_percent = order_percent
    shim.record = record
    shim.PerShare = PerShare
    shim.PriceSlippage = PriceSlippage
    shim.log = ENGINE.log
    # 在加载用户策略之前注册，策略中的from mindgo_api import *即可命中该模块。
    sys.modules["mindgo_api"] = shim


def load_strategy_module(strategy_path):
    """动态加载用户上传的策略文件；策略代码只在当前回测进程中执行。"""
    # 使用固定模块名但运行在一次性子进程内，不会与其他并发回测共享sys.modules。
    spec = importlib.util.spec_from_file_location("uploaded_mindgo_strategy", strategy_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def run(strategy_path):
    """
    回测总控制器。

    先创建引擎和兼容模块，再加载策略并执行 init；从 context 中取得标的后，
    按共同交易日循环推进四个阶段，最后统一导出结果。
    """
    global ENGINE
    # ENGINE必须先赋值，安装的兼容函数和log对象才能正确指向本次实例。
    ENGINE = MindgoBacktestEngine(strategy_path)
    install_mindgo_shim()
    strategy_module = load_strategy_module(strategy_path)

    if not hasattr(strategy_module, "init"):
        raise RuntimeError("MindGo 策略缺少 init(context) 入口")

    # init负责设置标的、佣金、滑点、基准等策略级参数。
    strategy_module.init(ENGINE.context)
    symbols = list(getattr(ENGINE.context, "valid_etfs", None) or getattr(ENGINE.context, "etf_list", []))
    if not symbols:
        raise RuntimeError("策略初始化后未提供有效标的列表")

    # 共同交易日历保证每个handle_bar都能拿到全部策略标的Bar。
    calendar = ENGINE.build_calendar(symbols)
    for current_date in calendar:
        # 每个交易日都重新准备数据，策略只能看到当前日期及其之前的行情，避免未来函数。
        ENGINE._prepare_daily_state(current_date, symbols)

        ENGINE._set_phase_time("before_trading")
        if hasattr(strategy_module, "before_trading"):
            strategy_module.before_trading(ENGINE.context)

        ENGINE._set_phase_time("handle_bar")
        if hasattr(strategy_module, "handle_bar"):
            strategy_module.handle_bar(ENGINE.context, ENGINE.current_bars)

        # handle_bar完成后从开盘成交标记切换为收盘估值，再执行盘后回调。
        ENGINE._mark_to_close()
        ENGINE._set_phase_time("after_trading")
        if hasattr(strategy_module, "after_trading"):
            strategy_module.after_trading(ENGINE.context)

        day_key = ENGINE.current_date.strftime("%Y-%m-%d")
        # 策略未主动record净值时，由引擎补写真实日终组合总资产。
        ENGINE.records.setdefault(day_key, {})
        ENGINE.records[day_key].setdefault("net_value", ENGINE.context.portfolio.total_value)

    ENGINE.export_results()


if __name__ == "__main__":
    # 本文件只由backtest_service创建的子进程直接执行。
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python mindgo_runner.py <strategy_path>")
    run(sys.argv[1])
