#!/usr/bin/env bash
# Run as root on the existing backend VM after copying a tested bootJar.
set -euo pipefail
JAR=${1:?path to tested bootJar}
test -s "$JAR"
java -version
id dulleobang >/dev/null 2>&1 || useradd --system --home /var/lib/dulleobang --shell /usr/sbin/nologin dulleobang
install -d -m 750 -o dulleobang -g dulleobang /opt/dulleobang /var/lib/dulleobang
install -d -m 700 /etc/dulleobang
# Keep database credentials and signing keys across deployments.
if [[ ! -f /etc/dulleobang/backend.env ]]; then
  umask 077
  python3 - <<'PY'
import secrets
from pathlib import Path
values = {
    'PORT': '8080', 'BASE_URL': 'http://34.47.87.3:8080',
    'DB_URL': 'jdbc:postgresql://127.0.0.1:5432/dulleobang_v2', 'DB_USER': 'dulleobang_app',
    'DB_PASSWORD': secrets.token_hex(32), 'JWT_SECRET': secrets.token_hex(48),
    'WORKER_JWT_SECRET': secrets.token_hex(48), 'STORAGE_SECRET': secrets.token_hex(48),
    'V2_DATA_DIR': '/var/lib/dulleobang', 'SAM3D_ENABLED': 'true',
    'SAM3D_URL': 'http://127.0.0.1:18766',
}
Path('/etc/dulleobang/backend.env').write_text(''.join(f'{k}={v}\n' for k,v in values.items()))
PY
fi
systemctl enable --now postgresql
python3 - <<'PY'
import subprocess
from pathlib import Path
env = dict(line.split('=', 1) for line in Path('/etc/dulleobang/backend.env').read_text().splitlines() if '=' in line)
def sql(statement):
    return subprocess.run(['runuser','-u','postgres','--','psql','-v','ON_ERROR_STOP=1','-At'], input=statement, text=True, check=True, capture_output=True).stdout.strip()
if sql("SELECT 1 FROM pg_roles WHERE rolname='dulleobang_app';") != '1':
    # Generated password is hex; no user-provided SQL is interpolated.
    password = env['DB_PASSWORD']
    if not all(c in '0123456789abcdef' for c in password):
        raise ValueError('Existing database account must be provisioned separately')
    sql("CREATE ROLE dulleobang_app LOGIN PASSWORD '" + password + "';")
if sql("SELECT 1 FROM pg_database WHERE datname='dulleobang_v2';") != '1':
    sql('CREATE DATABASE dulleobang_v2 OWNER dulleobang_app;')
PY
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
if [[ -f /opt/dulleobang/app.jar ]]; then
  cp -p /opt/dulleobang/app.jar "/opt/dulleobang/app-$STAMP.jar"
fi
install -m 640 -o dulleobang -g dulleobang "$JAR" /opt/dulleobang/app.jar
cat >/etc/systemd/system/dulleobang-backend.service <<'UNIT'
[Unit]
Description=Dulleobang Spring Boot API
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service
[Service]
User=dulleobang
Group=dulleobang
WorkingDirectory=/opt/dulleobang
EnvironmentFile=/etc/dulleobang/backend.env
ExecStart=/usr/bin/java -Xms128m -Xmx768m -Djava.awt.headless=true -jar /opt/dulleobang/app.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/dulleobang
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable dulleobang-backend
systemctl restart dulleobang-backend
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/health/ready >/dev/null; then
    systemctl is-active dulleobang-backend
    curl -i -s http://127.0.0.1:8080/
    exit 0
  fi
  sleep 2
done
journalctl -u dulleobang-backend -n 60 --no-pager
exit 1
