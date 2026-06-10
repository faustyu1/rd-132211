#!/bin/bash
cd /Users/faustyu/dev/rd-23
JAVA="/Users/faustyu/dev/rd-23/jdk-x64/jdk-21.0.11.jdk/Contents/Home/bin/java"
M2="$HOME/.m2/repository"
V=3.4.1

# LWJGL auto-extracts the right natives from the *-natives-* jars on the classpath,
# picking the classifier matching the JVM arch (macos / macos-arm64).
CP="target/classes:resources"
for a in lwjgl lwjgl-glfw lwjgl-vulkan lwjgl-shaderc; do
  CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V.jar"
  CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V-natives-macos.jar"
  CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V-natives-macos-arm64.jar"
done
CP="$CP:$M2/org/joml/joml/1.10.7/joml-1.10.7.jar"

"$JAVA" \
  -XstartOnFirstThread \
  -Dapple.awt.UIElement=true \
  -cp "$CP" \
  com.mojang.rubydung.RubyDung
