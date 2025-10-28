#!/bin/bash

# Production startup script for Solana Wallet Analyzer
# Optimized JVM settings for production deployment

echo "Starting Solana Wallet Analyzer in production mode..."

# JVM tuning flags for production
JVM_OPTS=(
    "-Xms1g"                           # Initial heap size
    "-Xmx2g"                           # Maximum heap size
    "-XX:+UseG1GC"                     # Use G1 garbage collector
    "-XX:MaxGCPauseMillis=200"         # Target max GC pause time
    "-XX:+UseStringDeduplication"      # Enable string deduplication
    "-XX:+OptimizeStringConcat"        # Optimize string concatenation
    "-XX:+UseCompressedOops"           # Use compressed object pointers
    "-XX:+UseCompressedClassPointers"  # Use compressed class pointers
    "-server"                          # Use server JVM
    "-Djava.awt.headless=true"         # Headless mode
    "-Dspring.profiles.active=prod"    # Production profile
    "-Dfile.encoding=UTF-8"            # UTF-8 encoding
)

# Application arguments
APP_ARGS=(
    "--spring.config.additional-location=classpath:application-prod.yml,classpath:application-info.yml"
)

# Create logs directory if it doesn't exist
mkdir -p logs

# Start the application
echo "JVM Options: ${JVM_OPTS[*]}"
echo "Application Arguments: ${APP_ARGS[*]}"
echo "Starting application..."

exec java "${JVM_OPTS[@]}" -jar build/libs/sol-0.0.1-SNAPSHOT.jar "${APP_ARGS[@]}"