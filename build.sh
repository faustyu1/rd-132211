#!/bin/bash
set -e
cd /Users/faustyu/dev/rd-23

JAVA_HOME="/Users/faustyu/dev/rd-23/jdk-x64/jdk-21.0.11.jdk/Contents/Home"
JAVAC="$JAVA_HOME/bin/javac"
CP="resources:/Users/faustyu/.m2/repository/org/lwjgl/lwjgl/lwjgl/2.9.3/lwjgl-2.9.3.jar:/Users/faustyu/.m2/repository/org/lwjgl/lwjgl/lwjgl_util/2.9.3/lwjgl_util-2.9.3.jar:/Users/faustyu/.m2/repository/net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar:/Users/faustyu/.m2/repository/net/java/jutils/jutils/1.0.0/jutils-1.0.0.jar"

mkdir -p target/classes
$JAVAC -d target/classes -cp "$CP" $(find sources -name "*.java")
echo "Build OK"
