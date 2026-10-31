#!/data/data/com.termux/files/usr/bin/bash
set -e
SRC="$HOME/LocalAI"
DEST="/sdcard/Download/LocalAi-Full-Backup"
STAMP=$(date +%Y%m%d-%H%M)
VERSION="${1:-dev}"

mkdir -p "$DEST"

echo "→ Backing up source…"
tar czf "$DEST/LocalAI-source-$STAMP.tar.gz" \
  -C "$SRC" \
  --exclude='./build' \
  --exclude='./app/build' \
  --exclude='./app/.cxx' \
  --exclude='./.gradle' \
  --exclude='./.idea' \
  --exclude='./local.properties' \
  --exclude='./app/src/main/cpp/llama.cpp' \
  --exclude='./app/src/main/cpp/stable-diffusion.cpp' \
  --exclude='./app/src/main/cpp/mllm-includes' \
  --exclude='./app/src/main/assets' \
  --exclude='./app/src/main/jniLibs' \
  .
echo "  ✓ LocalAI-source-$STAMP.tar.gz ($(ls -lh "$DEST/LocalAI-source-$STAMP.tar.gz" | awk '{print $5}'))"

APK="$SRC/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo "→ Copying APK…"
  cp "$APK" "$DEST/LocalAI-$VERSION-$STAMP.apk"
  echo "  ✓ LocalAI-$VERSION-$STAMP.apk ($(ls -lh "$DEST/LocalAI-$VERSION-$STAMP.apk" | awk '{print $5}'))"
else
  echo "  ⚠ No APK — run build first"
fi

echo "→ Copying configs…"
for f in gradle.properties keystore.properties localai-release.keystore; do
  [ -f "$SRC/$f" ] && cp "$SRC/$f" "$DEST/$f" && echo "  ✓ $f"
done
[ -f "$SRC/gradle/libs.versions.toml" ] && cp "$SRC/gradle/libs.versions.toml" "$DEST/libs.versions.toml" && echo "  ✓ libs.versions.toml"

echo ""
echo "Done → $DEST"
ls -lht "$DEST" | head -8
