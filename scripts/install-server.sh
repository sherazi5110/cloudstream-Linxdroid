#!/usr/bin/env bash
# Installs (or updates) the CloudStream web app server on Ubuntu (e.g. an Oracle Cloud "Always Free" server),
# with HTTPS for a free DuckDNS address. See docs/WEB-APP.md for the whole setup.
#
#   curl -fsSL https://raw.githubusercontent.com/sherazi5110/cloudstream-Linxdroid/main/scripts/install-server.sh \
#     | sudo bash -s -- --domain yourname --duckdns-token YOUR-TOKEN
#
# Update later (keeps your data):  ... | sudo bash -s -- --update
set -euo pipefail

REPO="sherazi5110/cloudstream-Linxdroid"
APP_DIR=/opt/cloudstream
DATA_DIR=/var/lib/cloudstream
CONF_DIR=/etc/cloudstream
PORT=8080
DOMAIN=""
TOKEN=""
UPDATE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2%.duckdns.org}"; shift 2 ;;
    --duckdns-token) TOKEN="$2"; shift 2 ;;
    --update) UPDATE=1; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
[ "$(id -u)" = 0 ] || { echo "Run it with sudo."; exit 1; }
. /etc/os-release
[ "${ID:-}" = ubuntu ] || echo "Warning: made for Ubuntu; this is ${PRETTY_NAME:-unknown}."

if [ "$UPDATE" = 0 ]; then
  [ -n "$DOMAIN" ] && [ -n "$TOKEN" ] || { echo "Give --domain <your duckdns name> and --duckdns-token <token>."; exit 1; }
fi

# ---------- packages ----------
say "Installing Java 21 and Caddy (the HTTPS web server)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q openjdk-21-jre-headless curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https python3 iptables-persistent >/dev/null
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q
  apt-get install -y -q caddy >/dev/null
fi

# ---------- the app ----------
say "Downloading the latest CloudStream release"
TAR_URL=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
  | python3 -c 'import json,sys; print(next(a["browser_download_url"] for a in json.load(sys.stdin)["assets"] if a["name"].startswith("cloudstream-linux-") and a["name"].endswith(".tar")))')
TMP=$(mktemp -d)
curl -fsSL "$TAR_URL" -o "$TMP/app.tar"
tar -xf "$TMP/app.tar" -C "$TMP"
rm -rf "$APP_DIR"
mv "$TMP/cloudstream-linux" "$APP_DIR"
rm -rf "$TMP"
echo "Installed $(basename "$TAR_URL")"

id cloudstream >/dev/null 2>&1 || useradd --system --home-dir "$DATA_DIR" --create-home --shell /usr/sbin/nologin cloudstream
mkdir -p "$DATA_DIR" "$CONF_DIR"
chown -R cloudstream:cloudstream "$DATA_DIR"

# Runs the app's commands as the server's user, on the server's data: sudo cloudstream-admin <command>.
cat > /usr/local/bin/cloudstream-admin <<EOF
#!/usr/bin/env bash
# Manage the CloudStream server: cloudstream-admin repo add <url> | install <name|all> | restore <backup.json> | web-password | update
set -e
if [ "\${1:-}" = restore ] && [ -n "\${2:-}" ]; then
  install -o cloudstream -g cloudstream -m 600 "\$2" "$DATA_DIR/restore.json"
  set -- restore "$DATA_DIR/restore.json"
fi
cd "$DATA_DIR"
sudo -u cloudstream env CLOUDSTREAM_HOME="$DATA_DIR" "$APP_DIR/bin/cloudstream-linux" "\$@"
case "\${1:-}" in restore|install|update|repo|uninstall|sideload) systemctl restart cloudstream && echo "Server restarted." ;; esac
EOF
chmod 755 /usr/local/bin/cloudstream-admin

cat > /etc/systemd/system/cloudstream.service <<EOF
[Unit]
Description=CloudStream web app server
After=network-online.target
Wants=network-online.target

[Service]
User=cloudstream
Environment=CLOUDSTREAM_HOME=$DATA_DIR
WorkingDirectory=$DATA_DIR
ExecStart=$APP_DIR/bin/cloudstream-linux serve --host 127.0.0.1 --port $PORT
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

if [ "$UPDATE" = 1 ]; then
  systemctl daemon-reload
  systemctl restart cloudstream
  say "Updated. Your address and data are unchanged."
  exit 0
fi

# ---------- password ----------
say "Choose the password for the web app (at least 6 characters)"
while true; do
  read -r -s -p "Password: " PW </dev/tty; echo
  read -r -s -p "Again: " PW2 </dev/tty; echo
  [ "$PW" = "$PW2" ] && [ ${#PW} -ge 6 ] && break
  echo "They don't match or it's too short. Try again."
done
sudo -u cloudstream env CLOUDSTREAM_HOME="$DATA_DIR" "$APP_DIR/bin/cloudstream-linux" web-password "$PW" >/dev/null
unset PW PW2

# ---------- DuckDNS ----------
say "Pointing $DOMAIN.duckdns.org at this server"
cat > "$CONF_DIR/duckdns.sh" <<EOF
#!/bin/sh
curl -fsS "https://www.duckdns.org/update?domains=$DOMAIN&token=$TOKEN&ip=" -o /dev/null
EOF
chmod 700 "$CONF_DIR/duckdns.sh"
"$CONF_DIR/duckdns.sh" || echo "Warning: DuckDNS didn't answer; check the name and token."
echo "*/5 * * * * root $CONF_DIR/duckdns.sh" > /etc/cron.d/duckdns

# ---------- HTTPS ----------
say "Setting up HTTPS"
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN.duckdns.org {
	reverse_proxy 127.0.0.1:$PORT {
		flush_interval -1
	}
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Robots-Tag "noindex, nofollow"
	}
}
EOF

# Oracle's Ubuntu images block incoming traffic in iptables too (besides the VCN rules in the web console).
for p in 80 443; do
  iptables -C INPUT -p tcp --dport $p -j ACCEPT 2>/dev/null || iptables -I INPUT 5 -p tcp --dport $p -j ACCEPT
done
netfilter-persistent save >/dev/null 2>&1 || true

systemctl daemon-reload
systemctl enable --now cloudstream >/dev/null
systemctl restart caddy

say "Done!"
cat <<EOF

  Open  https://$DOMAIN.duckdns.org  on your iPhone (the first visit can take a minute
  while the HTTPS certificate is made), log in, then Share -> Add to Home Screen.

  Bring your extensions, repositories, Live TV and library from the Linux app:
    1. Linux app: Settings -> Storage -> Back up...
    2. Copy the file here:   scp cloudstream-backup-*.json ubuntu@<server>:~
    3. On the server:        sudo cloudstream-admin restore ~/cloudstream-backup-*.json

  Other commands:  sudo cloudstream-admin repo add <url>      sudo cloudstream-admin install all
                   sudo cloudstream-admin web-password        (change the password)
                   sudo journalctl -u cloudstream -f           (server log)
EOF
