"""量化服务配置模型。

所有生产/演示配置统一从环境变量读取。配置模型在进程内缓存，避免每次HTTP请求都重新
解析`.env`；QMT路径、账号和交易开关属于高风险配置，不允许连接失败时自动降级到Mock。
"""

from functools import lru_cache

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """将`QUANT_`前缀环境变量转换为经过校验的强类型配置。"""

    # 同时兼容项目根目录与quant-service工作目录启动；未知字段忽略，便于共用根目录.env。
    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_prefix="QUANT_",
        extra="ignore",
        populate_by_name=True,
    )

    # 正常运行和本地演示都明确读取QMT；只有主动配置TEST_MOCK才允许使用测试数据。
    # 绝不能在QMT离线时静默回退到Mock，否则页面会把虚构资产误认为真实账户数据。
    mode: str = "QMT"
    # Spring调用内部接口时携带的共享令牌，不是用户JWT。
    internal_token: str = "development-internal-token"
    # 健康检查对外展示的服务版本。
    service_version: str = "1.0.0"
    # MiniQMT的userdata_mini目录；兼容新旧两种环境变量名。
    qmt_path: str = Field(
        default="",
        validation_alias=AliasChoices("QUANT_QMT_PATH", "QMT_PATH"),
    )
    # 只允许订阅指定资金账号，避免本机多个QMT账号之间串数据。
    qmt_account_id: str = Field(
        default="62283925",
        validation_alias=AliasChoices(
            "QUANT_QMT_ACCOUNT_ID", "ACCOUNT_ID", "QUANT_ACCOUNT_ID"
        ),
    )
    # XtQuant账户类型，当前股票/ETF交易使用STOCK。
    qmt_account_type: str = Field(
        default="STOCK",
        validation_alias=AliasChoices(
            "QUANT_QMT_ACCOUNT_TYPE", "ACCOUNT_TYPE", "QUANT_ACCOUNT_TYPE"
        ),
    )
    # 读取开关控制资产、持仓、委托和行情查询。
    qmt_read_enabled: bool = Field(
        default=True,
        validation_alias=AliasChoices(
            "QUANT_QMT_READ_ENABLED", "READ_ENABLED", "QUANT_READ_ENABLED"
        ),
    )
    # 交易开关默认关闭；开启后才允许向QMT模拟账户发送下单和撤单副作用。
    qmt_trade_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices(
            "QUANT_QMT_TRADE_ENABLED", "TRADE_ENABLED", "QUANT_TRADE_ENABLED"
        ),
    )

    @field_validator("mode", "qmt_account_type", mode="before")
    @classmethod
    def uppercase_modes(cls, value: object) -> str:
        """清理用户输入并统一为大写，避免`qmt`、`Stock`等大小写差异。"""
        return str(value).strip().upper()

    @field_validator("mode")
    @classmethod
    def validate_mode(cls, value: str) -> str:
        """拒绝未定义模式，防止拼写错误意外进入未知数据源。"""
        if value not in {"QMT", "TEST_MOCK"}:
            raise ValueError("QUANT_MODE must be QMT or TEST_MOCK")
        return value

    @field_validator("qmt_account_id", mode="before")
    @classmethod
    def normalize_account_id(cls, value: object) -> str:
        """资金账号只做文本化和去空格，不转数字，避免丢失潜在前导零。"""
        return str(value).strip()


@lru_cache
def get_settings() -> Settings:
    """返回进程级配置单例，降低高频内部接口的配置解析开销。"""
    return Settings()
