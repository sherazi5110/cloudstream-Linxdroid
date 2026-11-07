# CloudStream on iPhone (web app)

iPhones can't run CloudStream extensions, so this runs them on **your own small server**, and the iPhone uses
a web app from it. It opens in Safari, sits on your Home Screen like an app, and works anywhere, also over
mobile data. Only you can use it: it's behind your password.

```
iPhone (Safari web app)  --HTTPS-->  your server: CloudStream (extensions, Live TV, library)  -->  the sites
```

What you get: Home with each extension's catalogs, Search across all extensions, title pages with seasons
and episodes, the player (sources, subtitles, next-episode countdown, picture-in-picture, AirPlay),
Continue Watching, My list, history, Live TV and profiles (with PINs). Progress is saved on the server.

What's different from the apps:
- **Formats:** Safari plays MP4 and HLS streams (most sources), not MKV files or AC-3 / DTS audio. Sources
  marked "MKV" in the Sources list may not play; pick another.
- **No offline downloads** on the iPhone.
- **Extensions, repositories, Live TV sources and settings** are set up in the Linux app and copied to the
  server with a backup (below), or added with commands on the server.

Cost: nothing. You need an Oracle Cloud account (free; it asks for a bank card to check who you are, but the
"Always Free" server isn't charged) and a DuckDNS name (free). Setup takes about 30 minutes.

---

## 1. Create the free server (Oracle Cloud)

1. Sign up at **https://www.oracle.com/cloud/free/** → *Start for free*. Pick a **home region** close to you
   (it can't be changed later). The card check may show a small temporary charge that is refunded.
2. In the Oracle console: **☰ → Compute → Instances → Create instance**.
   - **Name:** `cloudstream`
   - **Image and shape → Edit:**
     - Image: **Canonical Ubuntu 24.04**
     - Shape: **Change shape → Ampere → VM.Standard.A1.Flex**, **2 OCPUs and 12 GB memory** (free).
       If it says *out of capacity*, try again later or another *availability domain*; or pick
       **VM.Standard.E2.1.Micro** (AMD, also free, slower but fine for one person).
   - **Networking:** keep the defaults (a new virtual cloud network, **public IPv4 address: yes**).
   - **Add SSH keys:** choose **Generate a key pair for me** and click **Save private key**. Keep that file
     (e.g. `ssh-key-2026-11-07.key`); it's your only way in.
   - Click **Create**. After a minute the instance is *Running*: copy its **Public IP address**.
3. Open the web ports: on the instance page click the **Subnet** link → **Security Lists** →
   *Default Security List* → **Add Ingress Rules**, and add two rules:
   - Source CIDR `0.0.0.0/0`, IP protocol TCP, destination port **80**
   - Source CIDR `0.0.0.0/0`, IP protocol TCP, destination port **443**

## 2. Get a free web address (DuckDNS)

1. Go to **https://www.duckdns.org** and sign in (Google, GitHub, …).
2. Type a name in *sub domain*, e.g. `syedcloud`, and click **add domain**. Your address is
   `syedcloud.duckdns.org`.
3. Paste the server's **public IP** into *current ip* next to it and click **update ip**.
4. Copy your **token** (the long code at the top of the page).

## 3. Install CloudStream on the server

On your Linux laptop, open a terminal in the folder with the key file:

```bash
chmod 600 ssh-key-*.key
ssh -i ssh-key-*.key ubuntu@YOUR-SERVER-IP
```

Answer `yes` the first time. You're now on the server. Run (with your DuckDNS name and token):

```bash
curl -fsSL https://raw.githubusercontent.com/sherazi5110/cloudstream-Linxdroid/main/scripts/install-server.sh \
  | sudo bash -s -- --domain syedcloud --duckdns-token YOUR-TOKEN
```

It installs Java, the CloudStream server and Caddy (which gets the free HTTPS certificate), asks you to
choose the **web app password**, and starts everything. It also restarts by itself after a reboot.

## 4. Copy your extensions, Live TV and library from the Linux app

1. In the Linux app: **Settings → Storage → Back up…** and save the file.
2. On your laptop (new terminal, in the folder with the key and the backup):
   ```bash
   scp -i ssh-key-*.key cloudstream-backup-*.json ubuntu@YOUR-SERVER-IP:~
   ```
3. On the server:
   ```bash
   sudo cloudstream-admin restore ~/cloudstream-backup-*.json
   ```
   It restores your repositories, profiles, My list, history, Live TV sources, Stremio add-ons and settings,
   downloads the extensions again, and restarts the server.

A backup from the **Android** app (Settings → Updates and backup → Back up) works the same way.

Or add things directly on the server:
```bash
sudo cloudstream-admin repo add https://example.com/repo.json   # or a shortcode
sudo cloudstream-admin plugins                                  # list what the repositories offer
sudo cloudstream-admin install all                              # or one extension by name
```

## 5. On the iPhone

1. Open **https://syedcloud.duckdns.org** in **Safari** (the very first visit can take a minute while the
   certificate is made).
2. Log in with your password. Pick your profile.
3. Tap **Share → Add to Home Screen → Add**. CloudStream now has an icon and opens full screen.

You stay logged in on that phone. *More → Log out of this device* logs it out.

---

## Looking after it

| What | Command (on the server) |
|---|---|
| Update CloudStream to the newest release | `curl -fsSL https://raw.githubusercontent.com/sherazi5110/cloudstream-Linxdroid/main/scripts/install-server.sh \| sudo bash -s -- --update` |
| Change the password (logs every device out) | `sudo cloudstream-admin web-password` |
| Update extensions | `sudo cloudstream-admin update` |
| Copy a newer backup over | `sudo cloudstream-admin restore ~/backup.json` |
| See the server log | `sudo journalctl -u cloudstream -f` |
| Restart | `sudo systemctl restart cloudstream` |

Extensions are also updated automatically each time the server starts (Settings → Extensions → *Update on
startup*, which comes over with the backup).

## If something doesn't work

- **The address doesn't open:** check both Oracle ingress rules (80 and 443), that DuckDNS shows the server's
  IP, and `sudo systemctl status caddy cloudstream`.
- **"Wrong password" after several tries:** each wrong try makes the next one wait a little longer (protects
  against guessing); wait a minute.
- **A video doesn't play:** open **Sources** and pick another one; MKV files and some audio formats don't play
  in Safari. HLS sources work best.
- **Oracle stopped the server:** Oracle can reclaim *Always Free* servers that sit idle for a long time.
  Using it regularly keeps it, or you can upgrade the account to *Pay As You Go* (Always Free resources stay free).

## Keep it private

The server passes video from the sites to your phone. Hosting companies don't allow running a public
streaming service, so keep it to yourself: don't share the address and password publicly.
