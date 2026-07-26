#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
# The working directory decides where settings.properties, servers.properties and
# saves/ live; run2.sh points it elsewhere so a second local client keeps its own.
cd "${RD_RUN_DIR:-$DIR}"

M2="$HOME/.m2/repository"
V=3.4.1

# Toolchain: $JAVA_HOME first, then PATH. The classes are compiled for Java 21.
jdkMajor() { "$1" -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1; }
JAVA=""
for c in ${JAVA_HOME:+"$JAVA_HOME/bin/java"} "$(command -v java || true)"; do
  [ -x "$c" ] || continue
  if [ "$(jdkMajor "$c")" -ge 21 ] 2>/dev/null; then JAVA="$c"; break; fi
done
[ -n "$JAVA" ] || { echo "no JRE 21+ found: set JAVA_HOME or put java on PATH" >&2; exit 1; }
[ -d "$DIR/target/classes" ] || { echo "nothing built in $DIR/target/classes: run ./build.sh first" >&2; exit 1; }

need() {
  [ -f "$1" ] || { echo "missing jar: $1" >&2
                   echo "populate ~/.m2 first: mvn -q dependency:resolve" >&2; exit 1; }
}

# LWJGL auto-extracts the right natives from the *-natives-* jars on the classpath,
# picking the classifier matching the JVM arch (macos / macos-arm64).
CP="$DIR/target/classes:$DIR/resources"
natives=0
for a in lwjgl lwjgl-glfw lwjgl-vulkan lwjgl-shaderc; do
  need "$M2/org/lwjgl/$a/$V/$a-$V.jar"
  CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V.jar"
  for n in natives-macos natives-macos-arm64; do
    if [ -f "$M2/org/lwjgl/$a/$V/$a-$V-$n.jar" ]; then
      CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V-$n.jar"
      natives=$((natives + 1))
    fi
  done
done
[ "$natives" -gt 0 ] || { echo "no LWJGL macOS natives jars under $M2: mvn -q dependency:resolve" >&2; exit 1; }
need "$M2/org/joml/joml/1.10.7/joml-1.10.7.jar"
CP="$CP:$M2/org/joml/joml/1.10.7/joml-1.10.7.jar"

exec "$JAVA" \
  -XstartOnFirstThread \
  -Dapple.awt.UIElement=true \
  -cp "$CP" \
  com.mojang.rubydung.RubyDung
