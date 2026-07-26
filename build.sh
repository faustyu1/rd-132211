#!/bin/bash
set -e
cd "$(dirname "$0")"

M2="$HOME/.m2/repository"
V=3.4.1

# Toolchain: $JAVA_HOME first, then PATH. The sources use Java 21 features (records,
# Math.clamp), so an older candidate is skipped instead of failing mid-compile.
jdkMajor() { "$1" -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1; }
JAVAC=""
for c in ${JAVA_HOME:+"$JAVA_HOME/bin/javac"} "$(command -v javac || true)"; do
  [ -x "$c" ] || continue
  if [ "$(jdkMajor "$c")" -ge 21 ] 2>/dev/null; then JAVAC="$c"; break; fi
done
[ -n "$JAVAC" ] || { echo "no JDK 21+ found: set JAVA_HOME or put javac on PATH" >&2; exit 1; }

need() {
  [ -f "$1" ] || { echo "missing jar: $1" >&2
                   echo "populate ~/.m2 first: mvn -q dependency:resolve" >&2; exit 1; }
}

CP="resources"
for a in lwjgl lwjgl-glfw lwjgl-vulkan lwjgl-shaderc; do
  need "$M2/org/lwjgl/$a/$V/$a-$V.jar"
  CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V.jar"
done
need "$M2/org/joml/joml/1.10.7/joml-1.10.7.jar"
CP="$CP:$M2/org/joml/joml/1.10.7/joml-1.10.7.jar"

mkdir -p target/classes
"$JAVAC" -d target/classes -cp "$CP" $(find sources -name "*.java")
echo "Build OK"
