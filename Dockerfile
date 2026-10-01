FROM python:3.13-slim
WORKDIR /srv/app
COPY requirements.lock pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir -c requirements.lock . && useradd --uid 10001 --create-home backend && mkdir /srv/data && chown backend:backend /srv/data
USER backend
ENV DATA_DIR=/srv/data
EXPOSE 8000
CMD ["uvicorn", "app.main:app_factory", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

