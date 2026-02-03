#!/bin/bash
# Configure nginx for DuckFlix Lite remote access

echo "🔧 Configuring nginx for DuckFlix Lite public access..."

# Backup existing nginx config
sudo cp /etc/nginx/sites-available/default /etc/nginx/sites-available/default.backup.$(date +%Y%m%d-%H%M%S)

# Check if lite_service location block exists
if sudo grep -q "location /lite_service/" /etc/nginx/sites-available/default; then
    echo "⚠️  lite_service location block already exists, updating..."
    # Remove old block and add new one
    sudo sed -i '/location \/lite_service\//,/^    }/d' /etc/nginx/sites-available/default
fi

# Find the server block and add lite_service location
sudo sed -i '/server {/a \
    # DuckFlix Lite API\n\
    location /lite_service/ {\n\
        proxy_pass http://localhost:3001/;\n\
        proxy_http_version 1.1;\n\
        proxy_set_header Upgrade $http_upgrade;\n\
        proxy_set_header Connection '\''upgrade'\'';\n\
        proxy_set_header Host $host;\n\
        proxy_cache_bypass $http_upgrade;\n\
        proxy_set_header X-Real-IP $remote_addr;\n\
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n\
        proxy_set_header X-Forwarded-Proto $scheme;\n\
    }\n' /etc/nginx/sites-available/default

# Test nginx configuration
echo "Testing nginx configuration..."
if sudo nginx -t; then
    echo "✅ Nginx configuration is valid"

    # Reload nginx
    echo "Reloading nginx..."
    sudo systemctl reload nginx
    echo "✅ Nginx reloaded"

    # Test the endpoint
    echo "Testing API endpoint..."
    sleep 2
    if curl -s -k https://duckflix.tv/lite_service/health | grep -q "ok"; then
        echo "✅ API endpoint is accessible!"
        curl -s -k https://duckflix.tv/lite_service/health | jq .
    else
        echo "❌ API endpoint test failed"
        echo "Response:"
        curl -s -k https://duckflix.tv/lite_service/health
    fi
else
    echo "❌ Nginx configuration test failed"
    echo "Restoring backup..."
    sudo cp /etc/nginx/sites-available/default.backup.* /etc/nginx/sites-available/default
    exit 1
fi

echo ""
echo "✅ Configuration complete!"
echo ""
echo "Test the API:"
echo "  curl -k https://duckflix.tv/lite_service/health"
echo ""
echo "Test login:"
echo "  curl -k https://duckflix.tv/lite_service/api/auth/login -X POST -H 'Content-Type: application/json' -d '{\"username\":\"Tawnia\",\"password\":\"jjjjjj\"}'"
