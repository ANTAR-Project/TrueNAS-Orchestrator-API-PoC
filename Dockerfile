FROM postgres:17-alpine
COPY postgres-init/ /docker-entrypoint-initdb.d/
RUN chmod +x /docker-entrypoint-initdb.d/*.sh