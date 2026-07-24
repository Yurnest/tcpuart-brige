#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# UART/DFU Bridge — Termux build setup script
# Run this ONCE inside Termux on your Android 15 phone, from the project dir.
# =============================================================================
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Step 1: Install build dependencies ==="
pkg update -y
pkg install -y openjdk-17 wget unzip aapt2

echo ""
echo "=== Step 2: Download gradle-wrapper.jar ==="
WRAPPER_JAR="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading gradle-wrapper.jar (Gradle 8.4)..."
    wget -q -O "$WRAPPER_JAR" \
        "https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar"
    echo "Downloaded."
else
    echo "gradle-wrapper.jar already present, skipping."
fi

echo ""
echo "=== Step 3: Verify Android SDK ==="
# Try common Termux SDK locations
if [ -z "$ANDROID_HOME" ]; then
    for candidate in \
        "$HOME/android-sdk" \
        "$HOME/sdk" \
        "/data/data/com.termux/files/home/android-sdk"; do
        if [ -d "$candidate/platform-tools" ]; then
            export ANDROID_HOME="$candidate"
            echo "Found SDK at $ANDROID_HOME"
            break
        fi
    done
fi

if [ -z "$ANDROID_HOME" ]; then
    echo ""
    echo "WARNING: Android SDK not found in common locations."
    echo "If you have the SDK elsewhere, set ANDROID_HOME before running ./gradlew"
    echo "or copy local.properties.template to local.properties and set sdk.dir."
    echo ""
    echo "To install a minimal SDK in Termux:"
    echo "  See: https://github.com/Lzhiyong/termux-ndk"
    echo "  Or install via sdkmanager if you have it."
else
    echo "Checking for required SDK components..."
    PLATFORMS="$ANDROID_HOME/platforms/android-35"
    BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"

    if [ ! -d "$PLATFORMS" ]; then
        echo "Installing platforms;android-35..."
        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" || \
        "$ANDROID_HOME/tools/bin/sdkmanager" "platforms;android-35" || \
        echo "  (sdkmanager not found — install manually)"
    else
        echo "  platforms;android-35: OK"
    fi

    if [ ! -d "$BUILD_TOOLS" ]; then
        echo "Installing build-tools;35.0.0..."
        "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "build-tools;35.0.0" || \
        "$ANDROID_HOME/tools/bin/sdkmanager" "build-tools;35.0.0" || \
        echo "  (sdkmanager not found — install manually)"
    else
        echo "  build-tools;35.0.0: OK"
    fi

    # Write local.properties
    echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
    echo "Written local.properties with sdk.dir=$ANDROID_HOME"
fi

echo ""
echo "=== Step 4: Make gradlew executable ==="
chmod +x "$PROJECT_DIR/gradlew"

echo ""
echo "================================================================"
echo "Setup complete.  Next steps:"
echo ""
echo "  1. Build the APK:"
echo "     cd $PROJECT_DIR"
echo "     ./gradlew assembleRelease"
echo ""
echo "  2. Generate a signing key (one-time):"
echo "     keytool -genkeypair -v -keystore release.keystore -alias bridge \\"
echo "       -keyalg RSA -keysize 2048 -validity 10000"
echo ""
echo "  3. Sign the APK:"
echo "     \$ANDROID_HOME/build-tools/35.0.0/apksigner sign \\"
echo "       --ks release.keystore \\"
echo "       app/build/outputs/apk/release/app-release-unsigned.apk"
echo ""
echo "  4. Install:"
echo "     adb install -r app/build/outputs/apk/release/app-release-unsigned.apk"
echo "     (or sideload via Android file manager + 'Install unknown apps')"
echo "================================================================"
