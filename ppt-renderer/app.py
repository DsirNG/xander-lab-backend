"""Service-authenticated, bounded rendering endpoint; never accepts code or remote resources."""
import asyncio
import hmac
import json
import logging
import os
from concurrent.futures import ProcessPoolExecutor

from fastapi import FastAPI, HTTPException, Request
from pydantic import ValidationError

from render import Deck, compile_deck

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
pool = ProcessPoolExecutor(max_workers=1)
busy = asyncio.Semaphore(1)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/v1/render")
async def render(request: Request):
    token = os.environ.get("PPT_RENDERER_TOKEN", "")
    if not token or not hmac.compare_digest(request.headers.get("authorization", ""), "Bearer " + token):
        raise HTTPException(401, "Unauthorized")
    if busy.locked():
        raise HTTPException(429, "Renderer busy; retry later")
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > 30 * 1024 * 1024:
            raise HTTPException(413, "Deck too large")
    try:
        deck = Deck.model_validate(json.loads(body))
        if any(len(bullet) > 120 for slide in deck.slides for bullet in slide.bullets):
            raise ValueError("Text block too long")
    except (ValidationError, ValueError, TypeError):
        raise HTTPException(400, "Invalid slide data") from None
    if busy.locked():
        raise HTTPException(429, "Renderer busy; retry later")
    async with busy:
        try:
            return await asyncio.get_running_loop().run_in_executor(pool, compile_deck, deck)
        except ValueError as error:
            detail = str(error)
            if detail.startswith(("PPT_TEXT_FIT:", "PPT_SVG_INVALID:", "PPT_ASSET_INVALID:")):
                raise HTTPException(422, detail[:350]) from None
            raise HTTPException(422, "Slide data could not be rendered") from None
        except Exception:
            logging.exception("PPT compilation failed")
            raise HTTPException(422, "Slide validation or native export failed") from None
