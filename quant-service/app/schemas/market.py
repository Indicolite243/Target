from pydantic import BaseModel, Field


class QuoteRequest(BaseModel):
    symbols: list[str] = Field(min_length=1, max_length=100)
    fields: list[str] = []
    preferredSource: str = "MOCK"
    environment: str = "SIMULATION"
