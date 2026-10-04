# syntax=docker/dockerfile:1.7
FROM python:3.11-slim AS builder
WORKDIR /build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
COPY pyproject.toml README.md ./
COPY sus_nexus_ai ./sus_nexus_ai
RUN python -m venv /opt/venv && /opt/venv/bin/pip install --upgrade pip \
    && /opt/venv/bin/pip install ".[kafka,observability]"

FROM python:3.11-slim AS runtime
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PATH="/opt/venv/bin:$PATH" \
    AI_ENVIRONMENT=prod AI_HTTP_PORT=8000 AI_AUTH_MODE=jwt AI_POLICY_MODE=opa \
    AI_IDENTITY_MODE=keycloak AI_LLM_PROVIDER=litellm
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --home /app app
WORKDIR /app
COPY --from=builder /opt/venv /opt/venv
COPY --chown=app:app sus_nexus_ai ./sus_nexus_ai
USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).status == 200 else 1)"
CMD ["sus-nexus-ai"]
