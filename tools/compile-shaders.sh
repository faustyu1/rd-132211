#!/bin/bash
# Regenerate resources/shaders/*.spv from the GLSL sources.
#
# The game itself never compiles GLSL: it loads the .spv files produced here. Run this after
# editing any shader, and commit the result alongside it. This is the only place shaderc is
# needed, which is why it is not a runtime dependency of the game.
set -e
cd "$(dirname "$0")/.."

M2="$HOME/.m2/repository"
V=3.4.1

jdkMajor() { "$1" -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1; }
JAVA=""
for c in ${JAVA_HOME:+"$JAVA_HOME/bin/java"} "$(command -v java || true)"; do
  [ -x "$c" ] || continue
  if [ "$(jdkMajor "$c")" -ge 21 ] 2>/dev/null; then JAVA="$c"; break; fi
done
[ -n "$JAVA" ] || { echo "no JRE 21+ found: set JAVA_HOME or put java on PATH" >&2; exit 1; }

need() {
  [ -f "$1" ] || { echo "missing jar: $1" >&2
                   echo "fetch the shader toolchain first: mvn -q -Pdev dependency:resolve" >&2; exit 1; }
}

CP=""
for a in lwjgl lwjgl-shaderc; do
  need "$M2/org/lwjgl/$a/$V/$a-$V.jar"
  CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V.jar"
  for n in natives-macos natives-macos-arm64 natives-linux natives-windows; do
    j="$M2/org/lwjgl/$a/$V/$a-$V-$n.jar"
    [ -f "$j" ] && CP="$CP:$j"
  done
done

echo "compiling shaders in resources/shaders"
exec "$JAVA" -cp "${CP#:}" tools/ShaderBuild.java resources/shaders
