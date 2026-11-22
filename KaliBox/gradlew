#!/bin/sh
# Minimal launcher. The CI workflow regenerates the full wrapper (script + jar)
# with `gradle wrapper` when gradle-wrapper.jar is missing.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar missing. Run: gradle wrapper --gradle-version 8.9" >&2
  exit 1
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
