#!/bin/bash

# Gradle wrapper script

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Use the gradle wrapper jar
GRADLE_WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"

# Check if wrapper jar exists
if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
    echo "Downloading Gradle wrapper..."
    mkdir -p gradle/wrapper
    curl -L -o "$GRADLE_WRAPPER_JAR" \
        "https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar"
fi

# Run gradle
exec "$JAVACMD" -jar "$GRADLE_WRAPPER_JAR" "$@"
