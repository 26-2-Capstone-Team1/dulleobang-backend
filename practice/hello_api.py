"""Teaching API only: memory storage, no authentication or production controls."""

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field

app = FastAPI(title="HTTP 입문 실습", version="1.0")
records: list[dict] = []


class PracticeInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=1, max_length=80)
    monthlyRent: int = Field(gt=0, strict=True)


@app.get("/hello")
def hello():
    return {"message": "서버가 응답했습니다."}


@app.post("/practice-listings", status_code=201)
def create_record(body: PracticeInput):
    record = {"id": len(records) + 1, **body.model_dump()}
    records.append(record)
    return record


@app.get("/practice-listings")
def get_records():
    return {"items": records}
