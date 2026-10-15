FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY backend/pyproject.toml backend/
COPY backend/lexet backend/lexet
COPY shared shared
RUN pip install --no-cache-dir "./backend[push]"
WORKDIR /app/backend
ENV LEXET_TAXONOMY=/app/shared/taxonomy.json
EXPOSE 8080
CMD ["lexet", "serve", "--port", "8080"]
