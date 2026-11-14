#!/bin/sh
# Bundles the libraries Nuvio scrapers require() into the app's assets.
# Usage: ./jsmodules/build.sh   (needs node)
set -e
cd "$(dirname "$0")"
npm install --silent
OUT=../app/src/main/assets/jsmodules
mkdir -p "$OUT"
for m in cheerio cryptojs; do
    npx esbuild $m.js --bundle --minify --platform=browser --format=iife --target=es2020 \
        --alias:stream=stream-browserify --inject:./inject.js --outfile="$OUT/$m.js"
done
