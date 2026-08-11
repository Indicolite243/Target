from functools import lru_cache

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_prefix="QUANT_",
        extra="ignore",
        populate_by_name=True,
    )

    mode: str = "SIMULATION"
    internal_token: str = "development-internal-token"
    service_version: str = "1.0.0"
    qmt_path: str = Field(
        default="",
        validation_alias=AliasChoices("QUANT_QMT_PATH", "QMT_PATH"),
    )
    qmt_account_id: str = Field(
        default="62283925",
        validation_alias=AliasChoices(
            "QUANT_QMT_ACCOUNT_ID", "ACCOUNT_ID", "QUANT_ACCOUNT_ID"
        ),
    )
    qmt_account_type: str = Field(
        default="STOCK",
        validation_alias=AliasChoices(
            "QUANT_QMT_ACCOUNT_TYPE", "ACCOUNT_TYPE", "QUANT_ACCOUNT_TYPE"
        ),
    )
    qmt_read_enabled: bool = Field(
        default=True,
        validation_alias=AliasChoices(
            "QUANT_QMT_READ_ENABLED", "READ_ENABLED", "QUANT_READ_ENABLED"
        ),
    )
    qmt_trade_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices(
            "QUANT_QMT_TRADE_ENABLED", "TRADE_ENABLED", "QUANT_TRADE_ENABLED"
        ),
    )

    @field_validator("mode", "qmt_account_type", mode="before")
    @classmethod
    def uppercase_modes(cls, value: object) -> str:
        return str(value).strip().upper()

    @field_validator("qmt_account_id", mode="before")
    @classmethod
    def normalize_account_id(cls, value: object) -> str:
        return str(value).strip()


@lru_cache
def get_settings() -> Settings:
    return Settings()
