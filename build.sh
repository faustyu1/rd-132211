#!/bin/bash
set -e
cd /Users/faustyu/dev/rd-23

JAVA_HOME="/Users/faustyu/dev/rd-23/jdk-x64/jdk-21.0.11.jdk/Contents/Home"
JAVAC="$JAVA_HOME/bin/javac"
M2="$HOME/.m2/repository"
V=3.4.1

CP="resources"
for a in lwjgl lwjgl-glfw lwjgl-vulkan lwjgl-shaderc; do
  CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V.jar"
done
CP="$CP:$M2/org/joml/joml/1.10.7/joml-1.10.7.jar"

mkdir -p target/classes
$JAVAC -d target/classes -cp "$CP" $(find sources -name "*.java")
echo "Build OK"
