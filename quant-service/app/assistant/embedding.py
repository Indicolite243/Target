"""百炼文本向量适配器；只接收已切片文本，不访问用户数据库或原始文件。"""
from pathlib import Path

import httpx
from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AssistantEmbeddingSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(Path(__file__).resolve().parents[3] / ".env",
                  Path(__file__).resolve().parents[3] / ".env.assistant.local"),
        extra="ignore",
    )
    api_key: SecretStr = Field(default=SecretStr(""), validation_alias="DASHSCOPE_API_KEY")
    credentials_file: Path | None = Field(default=None, validation_alias="ASSISTANT_LLM_CREDENTIALS_FILE")
    base_url: str = Field(default="https://dashscope.aliyuncs.com/compatible-mode/v1",
                          validation_alias="ASSISTANT_EMBEDDING_BASE_URL")
    model: str = Field(default="text-embedding-v4", validation_alias="ASSISTANT_EMBEDDING_MODEL")
    dimensions: int = Field(default=1024, ge=64, le=2048,
                            validation_alias="ASSISTANT_EMBEDDING_DIMENSIONS")
    timeout_seconds: float = Field(default=60, gt=0,
                                   validation_alias="ASSISTANT_EMBEDDING_TIMEOUT_SECONDS")

    @model_validator(mode="after")
    def load_referenced_key(self):
        if self.api_key.get_secret_value() or self.credentials_file is None:
            return self
        try:
            if self.credentials_file.stat().st_size > 128 * 1024:
                return self
            for line in self.credentials_file.read_text(encoding="utf-8-sig").splitlines():
                if line.startswith("DASHSCOPE_API_KEY="):
                    self.api_key = SecretStr(line.partition("=")[2].strip().strip("\"'"))
                    break
        except (OSError, UnicodeError):
            pass
        return self


class AssistantEmbeddingError(RuntimeError):
    """可以安全返回给 Java 的错误，不携带上游响应正文。"""


class QwenEmbeddingModel:
    def __init__(self, settings: AssistantEmbeddingSettings, transport=None):
        self.settings = settings
        self.transport = transport

    async def embed(self, inputs: list[str]) -> tuple[str, list[list[float]]]:
        if not self.settings.api_key.get_secret_value():
            raise AssistantEmbeddingError("百炼 Embedding 密钥尚未配置")
        if not inputs or len(inputs) > 10 or any(not value.strip() or len(value) > 8000 for value in inputs):
            raise AssistantEmbeddingError("Embedding 文本批次格式无效")
        payload = {
            "model": self.settings.model,
            "input": inputs,
            "dimensions": self.settings.dimensions,
            "encoding_format": "float",
        }
        try:
            async with httpx.AsyncClient(
                transport=self.transport,
                timeout=httpx.Timeout(self.settings.timeout_seconds, connect=10),
                follow_redirects=False,
            ) as client:
                response = await client.post(
                    self.settings.base_url.rstrip("/") + "/embeddings",
                    headers={"Authorization": "Bearer " + self.settings.api_key.get_secret_value()},
                    json=payload,
                )
            if response.status_code != 200:
                hints = {401: "百炼 Embedding 密钥无效或无访问权限",
                         403: "百炼 Embedding 模型访问被拒绝",
                         429: "百炼 Embedding 调用频率或额度受限"}
                raise AssistantEmbeddingError(hints.get(response.status_code, "百炼 Embedding 请求失败"))
            body = response.json()
            indexed = sorted(body.get("data") or [], key=lambda item: int(item.get("index", 0)))
            vectors = [item.get("embedding") for item in indexed]
            if len(vectors) != len(inputs) or any(not isinstance(vector, list)
                                                  or len(vector) != self.settings.dimensions
                                                  for vector in vectors):
                raise AssistantEmbeddingError("百炼 Embedding 返回数量或维度异常")
            return str(body.get("model") or self.settings.model), vectors
        except AssistantEmbeddingError:
            raise
        except httpx.TimeoutException:
            raise AssistantEmbeddingError("百炼 Embedding 响应超时") from None
        except httpx.HTTPError:
            raise AssistantEmbeddingError("百炼 Embedding 连接失败") from None
        except (ValueError, TypeError, AttributeError):
            raise AssistantEmbeddingError("百炼 Embedding 响应格式异常") from None
