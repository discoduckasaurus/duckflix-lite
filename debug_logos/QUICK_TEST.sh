#!/bin/bash

echo "==================================================================="
echo "DuckFlix Logo Debug - Quick Test Script"
echo "==================================================================="
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected!"
    echo "   Connect your device and enable USB debugging"
    exit 1
fi

echo "✓ Android device connected"
echo ""

# Step 1: Clear app data
echo "Step 1: Clearing app data..."
adb shell pm clear com.duckflix.lite
if [ $? -eq 0 ]; then
    echo "✓ App data cleared"
else
    echo "⚠ Could not clear app data (app might not be installed)"
fi
echo ""

# Step 2: Check if we need to rebuild
if [ "$1" == "--skip-build" ]; then
    echo "Step 2: Skipping rebuild (--skip-build flag set)"
else
    echo "Step 2: Rebuilding APK..."
    echo "   (This may take a minute...)"
    cd "$(dirname "$0")/../android" || exit 1
    ./gradlew clean > /dev/null 2>&1
    ./gradlew assembleDebug > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "✓ APK built successfully"

        # Install APK
        echo "   Installing APK..."
        adb install -r app/build/outputs/apk/debug/app-debug.apk > /dev/null 2>&1
        if [ $? -eq 0 ]; then
            echo "✓ APK installed"
        else
            echo "⚠ Could not install APK"
        fi
    else
        echo "❌ Build failed!"
        echo "   Run './gradlew assembleDebug' manually to see errors"
        exit 1
    fi
    cd - > /dev/null
fi
echo ""

# Step 3: Clear logcat
echo "Step 3: Clearing logcat buffer..."
adb logcat -c
echo "✓ Logcat cleared"
echo ""

# Step 4: Launch app
echo "Step 4: Launching DuckFlix Lite..."
adb shell am start -n com.duckflix.lite/.MainActivity > /dev/null 2>&1
echo "✓ App launched"
echo ""

echo "==================================================================="
echo "NOW WATCHING LOGS..."
echo "==================================================================="
echo ""
echo "Instructions:"
echo "1. Search for 'American Dad' in the app"
echo "2. Click on 'American Dad!' (TV show)"
echo "3. Click any 'Play' button"
echo "4. Watch the output below"
echo ""
echo "Looking for logo-related logs..."
echo "-------------------------------------------------------------------"
echo ""

# Watch logs
adb logcat | grep --line-buffered -E "\[LOGO-DEBUG|\[ERROR\].*logo|logoPath|logoUrl" | while IFS= read -r line; do
    # Highlight important lines
    if echo "$line" | grep -q "logoPath (raw):"; then
        echo "🔍 $line"
    elif echo "$line" | grep -q "logoUrl (computed):"; then
        echo "🔍 $line"
    elif echo "$line" | grep -q "Received logoUrl"; then
        echo "📥 $line"
    elif echo "$line" | grep -q "\[ERROR\]"; then
        echo "❌ $line"
    elif echo "$line" | grep -q "Failed to load logo"; then
        echo "❌ $line"
    else
        echo "$line"
    fi
done
