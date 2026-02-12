#!/bin/bash

# Simple script to check what logos TMDB has for American Dad and Zootopia
# Run this to see if TMDB even has logos for these titles

echo "==================================================================="
echo "Checking TMDB for logo availability"
echo "==================================================================="
echo ""
echo "NOTE: You need to set your TMDB_API_KEY environment variable"
echo "      export TMDB_API_KEY='your-key-here'"
echo ""

if [ -z "$TMDB_API_KEY" ]; then
    echo "ERROR: TMDB_API_KEY environment variable not set!"
    echo ""
    echo "Get your API key from: https://www.themoviedb.org/settings/api"
    echo "Then run: export TMDB_API_KEY='your-key'"
    exit 1
fi

echo "Testing American Dad! (TV ID: 1433)"
echo "-------------------------------------------------------------------"
AMERICAN_DAD_LOGOS=$(curl -s "https://api.themoviedb.org/3/tv/1433/images?api_key=$TMDB_API_KEY" | jq -r '.logos[] | select(.iso_639_1 == "en") | .file_path' | head -1)

if [ -n "$AMERICAN_DAD_LOGOS" ]; then
    echo "✓ English logo found: $AMERICAN_DAD_LOGOS"
    echo "  Full URL: https://image.tmdb.org/t/p/w500$AMERICAN_DAD_LOGOS"

    # Download it
    echo "  Downloading..."
    curl -s "https://image.tmdb.org/t/p/w500$AMERICAN_DAD_LOGOS" -o "American_Dad_logo.png"
    if [ -f "American_Dad_logo.png" ]; then
        echo "  ✓ Saved to: $(pwd)/American_Dad_logo.png"
        ls -lh American_Dad_logo.png
    fi
else
    echo "✗ No English logo found for American Dad!"
    echo "  Checking for any logos..."
    curl -s "https://api.themoviedb.org/3/tv/1433/images?api_key=$TMDB_API_KEY" | jq '.logos[] | {lang: .iso_639_1, path: .file_path}' | head -20
fi

echo ""
echo "Testing Zootopia (Movie ID: 269149)"
echo "-------------------------------------------------------------------"
ZOOTOPIA_LOGOS=$(curl -s "https://api.themoviedb.org/3/movie/269149/images?api_key=$TMDB_API_KEY" | jq -r '.logos[] | select(.iso_639_1 == "en") | .file_path' | head -1)

if [ -n "$ZOOTOPIA_LOGOS" ]; then
    echo "✓ English logo found: $ZOOTOPIA_LOGOS"
    echo "  Full URL: https://image.tmdb.org/t/p/w500$ZOOTOPIA_LOGOS"

    # Download it
    echo "  Downloading..."
    curl -s "https://image.tmdb.org/t/p/w500$ZOOTOPIA_LOGOS" -o "Zootopia_logo.png"
    if [ -f "Zootopia_logo.png" ]; then
        echo "  ✓ Saved to: $(pwd)/Zootopia_logo.png"
        ls -lh Zootopia_logo.png
    fi
else
    echo "✗ No English logo found for Zootopia!"
    echo "  Checking for any logos..."
    curl -s "https://api.themoviedb.org/3/movie/269149/images?api_key=$TMDB_API_KEY" | jq '.logos[] | {lang: .iso_639_1, path: .file_path}' | head -20
fi

echo ""
echo "==================================================================="
echo "Test complete!"
echo ""
echo "If logos were found and downloaded, you can view them:"
echo "  American Dad: $(pwd)/American_Dad_logo.png"
echo "  Zootopia: $(pwd)/Zootopia_logo.png"
echo "==================================================================="
