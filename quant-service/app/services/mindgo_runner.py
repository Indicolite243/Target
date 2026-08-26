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
    try:
        from xtquant import xtdata as module
        return module
    except Exception:
        candidates = []
        configured = os.environ.get("QUANT_XTQUANT_SITE_PACKAGES", "").strip()
        if configured:
            candidates.append(configured)
        user_sites = site.getusersitepackages()
        candidates.extend([user_sites] if isinstance(user_sites, str) else list(user_sites))
        candidates.append(str(Path(sys.base_prefix) / "Lib" / "site-packages"))
        for candidate in candidates:
            if candidate and Path(candidate).is_dir() and candidate not in sys.path:
                sys.path.append(candidate)
        try:
            import importlib
            importlib.invalidate_caches()
            return importlib.import_module("xtquant.xtdata")
        except Exception:
            return None


xtdata = _load_xtdata_module()


ENGINE = None


@dataclass
class PerShare:
    type: str = "stock"
    cost: float = 0.0
    min_trade_cost: float = 5.0


@dataclass
class PriceSlippage:
    perc: float = 0.0


@dataclass
class Position:
    amount: float = 0.0
    avg_cost: float = 0.0

    @property
    def available_amount(self):
        return self.amount

    @property
    def total_amount(self):
        return self.amount


@dataclass
class Bar:
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
    def info(self, message):
        print(f"[INFO] {message}")

    def warning(self, message):
        print(f"[WARN] {message}")

    def error(self, message):
        print(f"[ERROR] {message}")


class Portfolio:
    def __init__(self, engine):
        self._engine = engine
        self.starting_cash = float(os.environ.get("BACKTEST_INITIAL_CASH", "1000000"))
        self.cash = self.starting_cash
        self.positions = {}

    @property
    def market_value(self):
        total = 0.0
        for code, position in self.positions.items():
            total += position.amount * self._engine.current_mark_prices.get(code, 0.0)
        return float(total)

    @property
    def total_value(self):
        return float(self.cash + self.market_value)


class MindgoBacktestEngine:
    def __init__(self, strategy_path):
        self.strategy_path = Path(strategy_path).resolve()
        self.data_dir = Path(os.environ.get("BACKTEST_DATA_DIR", Path.cwd())).resolve()
        self.start_date = pd.Timestamp(os.environ.get("BACKTEST_START_DATE", "2020-01-01"))
        self.end_date = pd.Timestamp(os.environ.get("BACKTEST_END_DATE", "2025-01-01"))
        self.benchmark_symbol = os.environ.get("BACKTEST_BENCHMARK", "000300.SH")
        self.log = StrategyLogger()
        self.commission_rate = 0.0
        self.min_trade_cost = 5.0
        self.slippage_perc = 0.0
        self.volume_limit_ratio = float(os.environ.get("BACKTEST_VOLUME_LIMIT_RATIO", "0.25"))
        self.minute_volume_limit_ratio = float(os.environ.get("BACKTEST_MINUTE_VOLUME_LIMIT_RATIO", "0.5"))
        self.lot_size = max(1, int(os.environ.get("BACKTEST_LOT_SIZE", "100")))
        # SuperMind 使用回测区间十年期国债收益率均值；当前黄金样本对应约 1.95%。
        self.risk_free_rate = float(os.environ.get("BACKTEST_RISK_FREE_RATE", "0.0195"))
        self.current_dt = self.start_date.to_pydatetime()
        self.current_date = self.start_date
        self.current_phase = "init"
        self.current_bars = {}
        self.current_exec_prices = {}
        self.current_mark_prices = {}
        self.current_remaining_volume = {}
        self.records = {}
        self.trade_records = []
        self.data_cache = {}
        self.benchmark_cache = {}
        self.corporate_actions_cache = {}
        self.applied_corporate_actions = set()
        self.artifacts = {}
        self.context = SimpleNamespace()
        self.context.portfolio = Portfolio(self)
        self.context.run_params = {
            "start_date": self.start_date.strftime("%Y-%m-%d"),
            "end_date": self.end_date.strftime("%Y-%m-%d"),
        }

    def _load_single_symbol(self, code):
        if code in self.data_cache:
            return self.data_cache[code]

        file_path = self.data_dir / f"{code}.xlsx"
        if not file_path.exists():
            raise FileNotFoundError(f"未找到行情文件: {file_path}")

        df = pd.read_excel(file_path, engine="openpyxl")
        df.columns = [str(col).strip() for col in df.columns]
        if "time" not in df.columns:
            raise ValueError(f"{file_path.name} 缺少 time 列")

        df["time"] = pd.to_datetime(df["time"], errors="coerce")
        df = df.dropna(subset=["time"]).sort_values("time").set_index("time")

        for col in ["open", "high", "low", "close", "volume", "adjustment_nv"]:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce")

        for col in ["open", "high", "low", "close"]:
            df[col] = df.get(col, np.nan).ffill()
        df["volume"] = df.get("volume", 0).fillna(0.0)

        if "adjustment_nv" in df.columns:
            df["adjustment_nv"] = df["adjustment_nv"].ffill()
            valid_close = df["close"].replace(0, np.nan)
            pre_ratio = df["adjustment_nv"] / valid_close
            pre_ratio = pre_ratio.replace([np.inf, -np.inf], np.nan).ffill().fillna(1.0)
            df["open_pre"] = df["open"] * pre_ratio
            df["high_pre"] = df["high"] * pre_ratio
            df["low_pre"] = df["low"] * pre_ratio
            df["close_pre"] = df["adjustment_nv"]
        else:
            df["open_pre"] = df["open"]
            df["high_pre"] = df["high"]
            df["low_pre"] = df["low"]
            df["close_pre"] = df["close"]

        df = self._extend_with_xtdata(code, df)

        self.data_cache[code] = df
        return df

    def _extend_with_xtdata(self, code, frame):
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
        common_dates = frame.index.intersection(downloaded.index)
        if len(common_dates) > 0 and "close_pre" in frame.columns and "close_pre" in downloaded.columns:
            overlap_date = common_dates[-1]
            local_pre = float(frame.at[overlap_date, "close_pre"])
            downloaded_pre = float(downloaded.at[overlap_date, "close_pre"])
            if local_pre > 0 and downloaded_pre > 0:
                pre_scale = local_pre / downloaded_pre
                for col in ["open_pre", "high_pre", "low_pre", "close_pre"]:
                    downloaded[col] = downloaded[col] * pre_scale
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
        if xtdata is None:
            return None
        start_time = (self.start_date - pd.Timedelta(days=370)).strftime("%Y%m%d")
        end_time = self.end_date.strftime("%Y%m%d")
        xtdata.download_history_data(code, period="1d", start_time=start_time, end_time=end_time)
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
        parsed_index = pd.to_datetime(df.index.astype(str), format="%Y%m%d", errors="coerce")
        if parsed_index.isna().all() and "time" in df.columns:
            parsed_index = pd.to_datetime(df["time"], unit="ms", errors="coerce").dt.floor("D")
        df.index = parsed_index
        df = df[~df.index.isna()].sort_index()
        for col in ["open", "high", "low", "close", "volume"]:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce")
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
        file_path = self.data_dir / f"{code}.xlsx"
        if file_path.exists():
            return self._load_single_symbol(code)
        return self._load_xtdata_symbol(code)

    def get_price(self, code, start_date=None, end_date=None, bar_count=None, fre_step="1d", fields=None, skip_paused=True, fq=None):
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
        self.commission_rate = float(getattr(commission, "cost", 0.0))
        self.min_trade_cost = max(0.0, float(getattr(commission, "min_trade_cost", 5.0)))

    def set_slippage(self, slippage):
        self.slippage_perc = float(getattr(slippage, "perc", 0.0))

    def set_volume_limit(self, daily_ratio=0.25, minute_ratio=0.5):
        self.volume_limit_ratio = max(0.0, float(daily_ratio))
        self.minute_volume_limit_ratio = max(0.0, float(minute_ratio))

    def set_benchmark(self, benchmark_symbol):
        if benchmark_symbol:
            self.benchmark_symbol = str(benchmark_symbol)

    def _get_exec_price(self, code, is_buy):
        raw_price = float(self.current_exec_prices.get(code, 0.0))
        if raw_price <= 0:
            raw_price = float(self.current_mark_prices.get(code, 0.0))
        if raw_price <= 0:
            return 0.0
        half_slippage = self.slippage_perc / 2.0
        return raw_price * (1.0 + half_slippage if is_buy else 1.0 - half_slippage)

    def _consume_trade_volume(self, code, requested_qty):
        lot = float(self.lot_size)
        requested_qty = math.floor(max(0.0, float(requested_qty)) / lot) * lot
        remaining = float(self.current_remaining_volume.get(code, 0.0))
        actual_qty = min(requested_qty, math.floor(remaining / lot) * lot)
        self.current_remaining_volume[code] = max(0.0, remaining - actual_qty)
        return actual_qty

    def _trade_fee(self, trade_value):
        if trade_value <= 0:
            return 0.0
        return max(trade_value * self.commission_rate, self.min_trade_cost)

    def _append_trade(self, code, action, quantity, exec_price, fee):
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
        if code not in self.current_bars:
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
            previous_cost = position.avg_cost * position.amount
            position.amount += quantity
            position.avg_cost = (previous_cost + trade_value + fee) / position.amount if position.amount > 0 else 0.0
            self.context.portfolio.cash -= total_cost
        else:
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
            self.context.portfolio.cash += trade_value - fee
            position.amount -= quantity
            if position.amount <= 1e-12:
                self.context.portfolio.positions.pop(code, None)
            else:
                position.avg_cost = position.avg_cost

        self._append_trade(code, action, quantity, exec_price, fee)
        return SimpleNamespace(code=code, action=action, amount=quantity, price=exec_price)

    def order_target(self, code, target_amount):
        position = self.context.portfolio.positions.get(code)
        current_amount = position.amount if position else 0.0
        delta_amount = float(target_amount) - current_amount
        reference_price = float(self.current_mark_prices.get(code, 0.0))
        return self.order_value(code, delta_amount * reference_price)

    def order_target_value(self, code, target_value):
        current_value = 0.0
        if code in self.context.portfolio.positions:
            current_value = self.context.portfolio.positions[code].amount * float(self.current_mark_prices.get(code, 0.0))
        return self.order_value(code, float(target_value) - current_value)

    def order_target_percent(self, code, target_percent):
        target_value = self.context.portfolio.total_value * float(target_percent)
        return self.order_target_value(code, target_value)

    def order_percent(self, code, percent):
        delta_value = self.context.portfolio.total_value * float(percent)
        return self.order_value(code, delta_value)

    def record(self, **kwargs):
        day_key = self.current_date.strftime("%Y-%m-%d")
        self.records.setdefault(day_key, {})
        self.records[day_key].update(kwargs)

    def build_calendar(self, symbols):
        frames = [self._load_single_symbol(code) for code in symbols]
        calendar = frames[0].index
        for frame in frames[1:]:
            calendar = calendar.intersection(frame.index)
        calendar = calendar[(calendar >= self.start_date) & (calendar <= self.end_date)]
        return list(calendar)

    def build_bars(self, symbols, current_date):
        bar_dict = {}
        for code in symbols:
            frame = self._load_single_symbol(code)
            row = frame.loc[current_date]
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
        if code in self.corporate_actions_cache:
            return self.corporate_actions_cache[code]
        actions = {}
        if xtdata is not None:
            try:
                frame = xtdata.get_divid_factors(code)
                if frame is not None and not frame.empty:
                    for index, row in frame.iterrows():
                        digits = "".join(ch for ch in str(index) if ch.isdigit())[:8]
                        if len(digits) != 8:
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
        date_key = pd.Timestamp(current_date).strftime("%Y%m%d")
        for code in symbols:
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
                self.context.portfolio.cash += old_amount * interest
                position.avg_cost = max(0.0, position.avg_cost - interest)
            share_factor = 1.0 + action["stock_bonus"] + action["stock_gift"]
            if share_factor > 0 and abs(share_factor - 1.0) > 1e-12:
                position.amount = math.floor(old_amount * share_factor)
                position.avg_cost = position.avg_cost / share_factor
            allot_num = max(0.0, action["allot_num"])
            allot_price = max(0.0, action["allot_price"])
            if allot_num > 0 and allot_price > 0:
                allot_shares = math.floor(old_amount * allot_num)
                affordable = math.floor(self.context.portfolio.cash / allot_price)
                allot_shares = min(allot_shares, affordable)
                if allot_shares > 0:
                    previous_cost = position.avg_cost * position.amount
                    position.amount += allot_shares
                    self.context.portfolio.cash -= allot_shares * allot_price
                    position.avg_cost = (previous_cost + allot_shares * allot_price) / position.amount

    def _prepare_daily_state(self, current_date, symbols):
        self.current_date = pd.Timestamp(current_date)
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
        self.current_remaining_volume = {
            code: math.floor(max(0.0, bar.volume * self.volume_limit_ratio) / self.lot_size) * self.lot_size
            for code, bar in self.current_bars.items()
        }

    def _mark_to_close(self):
        self.current_mark_prices = {
            code: (bar.raw_close if bar.raw_close > 0 else bar.raw_open)
            for code, bar in self.current_bars.items()
        }

    def _set_phase_time(self, phase):
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
        current_ts = pd.Timestamp(current_date)
        eligible = series.loc[series.index <= current_ts]
        if len(eligible) == 0:
            eligible = series.loc[series.index >= current_ts]
        if len(eligible) == 0:
            raise KeyError(f"找不到 {current_ts.strftime('%Y-%m-%d')} 对应的行情数据")
        return float(eligible.iloc[-1] if eligible.index[-1] <= current_ts else eligible.iloc[0])

    def export_results(self):
        if not self.records:
            raise RuntimeError("策略运行结束后没有记录任何净值数据")

        ordered_dates = sorted(self.records.keys())
        start_cash = self.context.portfolio.starting_cash
        benchmark_df = self._load_benchmark_symbol(self.benchmark_symbol)

        first_trade_date = pd.Timestamp(ordered_dates[0])
        benchmark_close_series = pd.to_numeric(benchmark_df["close"], errors="coerce").dropna()
        benchmark_history = benchmark_close_series.loc[benchmark_close_series.index < first_trade_date]
        benchmark_base = float(benchmark_history.iloc[-1]) if len(benchmark_history) > 0 else None

        strategy_nav = []
        benchmark_nav = []

        for date_str in ordered_dates:
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

        total_return_decimal = float(strategy_nav[-1] - 1.0)
        benchmark_total_return_decimal = float(benchmark_nav[-1] - 1.0)
        annual_return_decimal = float(strategy_nav[-1] ** (250.0 / n_daily) - 1.0) if n_daily else 0.0
        benchmark_annual_return_decimal = float(benchmark_nav[-1] ** (250.0 / n_daily) - 1.0) if n_daily else 0.0
        total_return = total_return_decimal * 100.0
        benchmark_total_return = benchmark_total_return_decimal * 100.0
        annual_return = annual_return_decimal * 100.0
        benchmark_annual_return = benchmark_annual_return_decimal * 100.0

        risk_free_rate = self.risk_free_rate

        alpha = annual_return_decimal - risk_free_rate - beta * (benchmark_annual_return_decimal - risk_free_rate)
        sharpe = (annual_return_decimal - risk_free_rate) / volatility if volatility > 0 else 0.0

        if len(active_return) > 1:
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
            downside_returns = np.where(
                paired["strategy"].to_numpy(dtype=float) < paired["benchmark"].to_numpy(dtype=float),
                paired["strategy"].to_numpy(dtype=float) - paired["benchmark"].to_numpy(dtype=float),
                0.0,
            )
            downside_risk = float(np.sqrt((250.0 / len(paired)) * np.square(downside_returns).sum()))
        else:
            downside_risk = 0.0

        sortino = (annual_return_decimal - risk_free_rate) / downside_risk if downside_risk > 0 else 0.0
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

        result_json_path = os.environ.get("BACKTEST_RESULT_JSON_PATH")
        target_json_path = Path(result_json_path) if result_json_path else (self.data_dir / "strategy_performance.json")
        with open(target_json_path, "w", encoding="utf-8") as f:
            json.dump(json_payload, f, ensure_ascii=False, indent=2)
        return json_payload

    def _export_markdown(self):
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
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        excel_path = self.data_dir / f"ETF_strategy_trades_{timestamp}.xlsx"
        wb = Workbook()
        ws = wb.active
        ws.title = "所有交易"
        headers = ["日期", "代码", "操作", "数量", "价格", "金额", "佣金"]
        ws.append(headers)
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
    return ENGINE.current_dt


def get_price(*args, **kwargs):
    return ENGINE.get_price(*args, **kwargs)


def set_commission(*args, **kwargs):
    return ENGINE.set_commission(*args, **kwargs)


def set_slippage(*args, **kwargs):
    return ENGINE.set_slippage(*args, **kwargs)


def set_volume_limit(*args, **kwargs):
    return ENGINE.set_volume_limit(*args, **kwargs)


def set_benchmark(*args, **kwargs):
    return ENGINE.set_benchmark(*args, **kwargs)


def order_value(*args, **kwargs):
    return ENGINE.order_value(*args, **kwargs)


def order_target(*args, **kwargs):
    return ENGINE.order_target(*args, **kwargs)


def order_target_value(*args, **kwargs):
    return ENGINE.order_target_value(*args, **kwargs)


def order_target_percent(*args, **kwargs):
    return ENGINE.order_target_percent(*args, **kwargs)


def order_percent(*args, **kwargs):
    return ENGINE.order_percent(*args, **kwargs)


def record(**kwargs):
    return ENGINE.record(**kwargs)


def install_mindgo_shim():
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
    sys.modules["mindgo_api"] = shim


def load_strategy_module(strategy_path):
    spec = importlib.util.spec_from_file_location("uploaded_mindgo_strategy", strategy_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def run(strategy_path):
    global ENGINE
    ENGINE = MindgoBacktestEngine(strategy_path)
    install_mindgo_shim()
    strategy_module = load_strategy_module(strategy_path)

    if not hasattr(strategy_module, "init"):
        raise RuntimeError("MindGo 策略缺少 init(context) 入口")

    strategy_module.init(ENGINE.context)
    symbols = list(getattr(ENGINE.context, "valid_etfs", None) or getattr(ENGINE.context, "etf_list", []))
    if not symbols:
        raise RuntimeError("策略初始化后未提供有效标的列表")

    calendar = ENGINE.build_calendar(symbols)
    for current_date in calendar:
        ENGINE._prepare_daily_state(current_date, symbols)

        ENGINE._set_phase_time("before_trading")
        if hasattr(strategy_module, "before_trading"):
            strategy_module.before_trading(ENGINE.context)

        ENGINE._set_phase_time("handle_bar")
        if hasattr(strategy_module, "handle_bar"):
            strategy_module.handle_bar(ENGINE.context, ENGINE.current_bars)

        ENGINE._mark_to_close()
        ENGINE._set_phase_time("after_trading")
        if hasattr(strategy_module, "after_trading"):
            strategy_module.after_trading(ENGINE.context)

        day_key = ENGINE.current_date.strftime("%Y-%m-%d")
        ENGINE.records.setdefault(day_key, {})
        ENGINE.records[day_key].setdefault("net_value", ENGINE.context.portfolio.total_value)

    ENGINE.export_results()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python mindgo_runner.py <strategy_path>")
    run(sys.argv[1])
