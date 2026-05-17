# TextScanner — Build with GitHub Actions (Phone-Friendly Guide)

## One-time Setup (do this once, never again)

### Step 1 — Create GitHub repo
1. Open **github.com** on your phone browser
2. Tap **+** → **New repository**
3. Name: `TextScanner` | Set to **Private** → Create

### Step 2 — Upload the project zip
1. In your new repo, tap **Add file → Upload files**
2. Upload `TextScanner_Android.zip`
3. After upload, GitHub will show the zip file in the repo

> **Or** extract the zip and upload all files/folders directly — GitHub supports folder upload from phone via the web interface.

---

### Step 3 — Generate a Keystore (needed ONCE for signing)

You need Java for this. Two options:

#### Option A — Use GitHub Actions itself to generate (easiest from phone)
Create a file `.github/workflows/gen_keystore.yml` in your repo with this content,
run it once, copy the output, then delete the file:

```yaml
name: Generate Keystore (run once)
on: workflow_dispatch
jobs:
  gen:
    runs-on: ubuntu-latest
    steps:
      - name: Generate and print keystore
        run: |
          keytool -genkey -v -keystore ts.jks -alias textscanner \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass textscanner123 -keypass textscanner123 \
            -dname "CN=TextScanner,OU=App,O=Hanif,L=BD,S=BD,C=BD"
          echo "=== COPY THIS BASE64 ==="
          base64 -w 0 ts.jks
```

1. Go to **Actions** tab → Run this workflow
2. Click the finished run → expand the step → **copy the long base64 string**
3. That's your `KEYSTORE_BASE64` secret value

#### Option B — Use Replit / any online terminal
```bash
keytool -genkey -v -keystore ts.jks -alias textscanner \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass textscanner123 -keypass textscanner123 \
  -dname "CN=TextScanner,OU=App,O=Hanif,L=BD,S=BD,C=BD"
base64 -w 0 ts.jks
```

---

### Step 4 — Add GitHub Secrets

Go to your repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

Add these 4 secrets:

| Secret Name | Value |
|---|---|
| `KEYSTORE_BASE64` | The long base64 string from Step 3 |
| `KEY_ALIAS` | `textscanner` |
| `KEY_PASSWORD` | `textscanner123` |
| `STORE_PASSWORD` | `textscanner123` |

---

### Step 5 — Build!

Every time you push code to `main`, GitHub Actions automatically:
1. Bumps the `versionCode` (so updates install without uninstalling)
2. Builds a signed release APK
3. Creates a GitHub Release with the APK attached

You can also trigger manually: **Actions** tab → **Build & Release TextScanner APK** → **Run workflow**

---

## Installing Updates on Your Phone

### First install
1. Go to **Releases** (right side of your repo page)
2. Download the `.apk` file
3. Open it → Allow "Install from unknown sources" if asked → Install

### Every future update
1. Download the new `.apk` from Releases
2. Open it → tap **Update** (NOT uninstall — it keeps all your data!)

✅ This works because every build uses the **same keystore** (same signature).
Android allows updates only when the signature matches. The increasing `versionCode` tells Android it's a newer version.

---

## How to Update the App Code from Phone

1. Go to your repo on GitHub
2. Navigate to any file (e.g. `app/src/main/java/.../ui/MainActivity.kt`)
3. Tap the **pencil icon** ✏️ to edit
4. Make changes → **Commit changes**
5. GitHub Actions builds automatically → new APK in Releases in ~5 mins

---

## Troubleshooting

**Build fails with "keystore not found"**
→ Check your `KEYSTORE_BASE64` secret is correct. Re-run the keytool command.

**"App not installed" on phone**
→ You have a different signature from a previous install. Uninstall the old version first, then install. After that, all updates will work.

**versionCode not bumping**
→ The workflow edits `build.gradle` in the runner only, not in your repo. This is intentional — your repo `versionCode` stays at 1 but each release APK gets a unique code from `$GITHUB_RUN_NUMBER`.
