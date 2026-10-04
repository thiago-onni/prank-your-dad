"""Aplicação FastAPI do ai-service."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

from sus_nexus_ai.api.auth import JwtVerifier
from sus_nexus_ai.api.routes import router
from sus_nexus_ai.common import configure_logging, get_logger
from sus_nexus_ai.config import Settings, get_settings
from sus_nexus_ai.service import AIService, build_service

log = get_logger(__name__)

PROBLEM_JSON = "application/problem+json"
API_VERSION = "0.2.0"


def _operation_id(route: APIRoute) -> str:
    """``operationId`` = nome da função da rota (estável para geração de tipos no web)."""
    return route.name


def _problem(status: int, title: str, detail: str | None = None, **extra: object) -> JSONResponse:
    body: dict[str, object] = {"type": "about:blank", "title": title, "status": status}
    if detail:
        body["detail"] = detail
    body.update(extra)
    return JSONResponse(status_code=status, content=body, media_type=PROBLEM_JSON)


def create_app(settings: Settings | None = None, service: AIService | None = None) -> FastAPI:
    settings = settings or (service.settings if service else get_settings())
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.settings = settings
        app.state.service = service or build_service(settings)
        app.state.jwt_verifier = JwtVerifier(settings)
        consumer = None
        if settings.kafka_enabled:
            from sus_nexus_ai.consumers.kafka import KafkaAgentConsumer

            consumer = KafkaAgentConsumer(app.state.service, settings)
            await consumer.start()
        log.info("app.started", environment=settings.environment, auth_mode=settings.auth_mode)
        try:
            yield
        finally:
            if consumer is not None:
                await consumer.stop()
            await app.state.service.aclose()

    app = FastAPI(
        title="SUS Nexus — AI Service",
        version=API_VERSION,
        description=(
            "Agentes de IA com ferramentas formais, OPA, kill switch e aprovação humana. "
            "Contrato exportado para contracts/openapi/ai-service.yaml "
            "(python -m sus_nexus_ai.export_openapi)."
        ),
        lifespan=lifespan,
        servers=[{"url": "http://localhost:8000", "description": "dev local"}],
        generate_unique_id_function=_operation_id,
    )
    # Disponibiliza settings/service antes do lifespan para dependências em testes síncronos.
    app.state.settings = settings
    if service is not None:
        app.state.service = service
        app.state.jwt_verifier = JwtVerifier(settings)
    app.include_router(router)

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
        return _problem(exc.status_code, _title(exc.status_code), str(exc.detail))

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        return _problem(
            422,
            "Unprocessable Entity",
            "corpo ou parâmetros inválidos",
            errors=[{"loc": list(e["loc"]), "msg": e["msg"]} for e in exc.errors()],
        )

    return app


def _title(status: int) -> str:
    return {
        400: "Bad Request",
        401: "Unauthorized",
        403: "Forbidden",
        404: "Not Found",
        409: "Conflict",
        422: "Unprocessable Entity",
        500: "Internal Server Error",
        503: "Service Unavailable",
    }.get(status, "Error")


def main() -> None:  # pragma: no cover - entrypoint
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "sus_nexus_ai.api.app:create_app",
        factory=True,
        host="0.0.0.0",
        port=settings.http_port,
        log_level=settings.log_level.lower(),
    )
