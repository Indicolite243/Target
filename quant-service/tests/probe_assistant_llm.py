"""手动连接验证：只发送固定问候，不发送账户数据，不打印凭证。"""
import argparse
import asyncio
import sys
from pathlib import Path

from dotenv import dotenv_values

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.assistant.llm import AssistantModelError, AssistantModelSettings, QwenStreamingModel


async def probe(credentials_file: str, through_api: bool = False) -> int:
    key = dotenv_values(credentials_file).get("DASHSCOPE_API_KEY", "")
    settings = AssistantModelSettings(DASHSCOPE_API_KEY=key)
    if through_api:
        import httpx
        from app.main import app, authorize
        from app.assistant.api import get_model
        # 仅本进程 ASGI 验证；不启动端口、不连接 QMT、不影响运行中的服务。
        app.dependency_overrides[authorize] = lambda: None
        app.dependency_overrides[get_model] = lambda: QwenStreamingModel(settings)
        try:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local-test") as client:
                response = await client.post("/internal/v1/assistant/stream", json={
                    "messages": [{"role": "user", "content": "这是连接测试，请只回复：连接成功。"}]
                })
                # 只输出受控接口的事件名称，不打印头部或密钥。
                events = [line for line in response.text.splitlines() if line.startswith("event:")]
                print(f"http={response.status_code}; events={events}")
                return 0 if response.status_code == 200 and "event: done" in events and "event: error" not in events else 1
        finally:
            app.dependency_overrides.clear()
    try:
        chunks = []
        async for chunk in QwenStreamingModel(settings).stream([
            {"role": "user", "content": "这是连接测试，请只回复：连接成功。"}
        ]):
            chunks.append(chunk)
        print(f"model={settings.model}; chunks={len(chunks)}; response={''.join(chunks)}")
        return 0
    except AssistantModelError as exc:
        print(str(exc))
        return 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--credentials-file", required=True)
    parser.add_argument("--through-api", action="store_true")
    args = parser.parse_args()
    raise SystemExit(asyncio.run(probe(args.credentials_file, args.through_api)))
