#!/bin/bash
# ============================================================
# Run this ONE TIME on any machine that has Java installed.
# It creates your signing keystore and prints everything
# you need to paste into GitHub Secrets.
# ============================================================

echo "=== TextScanner Keystore Generator ==="
echo ""

# Generate the keystore
keytool -genkey -v \
  -keystore textscanner.jks \
  -alias textscanner \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass textscanner123 \
  -keypass textscanner123 \
  -dname "CN=Hanif, OU=Dev, O=TextScanner, L=BD, ST=BD, C=BD"

echo ""
echo "============================================"
echo "  Paste these into GitHub → Settings → Secrets → Actions"
echo "============================================"
echo ""
echo "Secret name: KEYSTORE_BASE64"
echo "Secret value:"
base64 -w 0 textscanner.jks
echo ""
echo ""
echo "Secret name: KEY_ALIAS"
echo "Secret value: textscanner"
echo ""
echo "Secret name: KEY_PASSWORD"
echo "Secret value: textscanner123"
echo ""
echo "Secret name: STORE_PASSWORD"
echo "Secret value: textscanner123"
echo ""
echo "IMPORTANT: Save textscanner.jks somewhere safe!"
echo "You need the SAME keystore for every update."
