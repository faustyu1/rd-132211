#!/bin/bash
mkdir -p /tmp/rd23-client2
cd /tmp/rd23-client2
JAVA="/Users/faustyu/dev/rd-23/jdk-x64/jdk-21.0.11.jdk/Contents/Home/bin/java"
M2="$HOME/.m2/repository"
"$JAVA" \
  -XstartOnFirstThread \
  -Dapple.awt.UIElement=true \
  -Djava.library.path=/Users/faustyu/dev/rd-23/target/natives \
  -cp "/Users/faustyu/dev/rd-23/target/classes:/Users/faustyu/dev/rd-23/resources:$M2/org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1.jar:$M2/org/lwjgl/lwjgl-glfw/3.4.1/lwjgl-glfw-3.4.1.jar:$M2/org/lwjgl/lwjgl-opengl/3.4.1/lwjgl-opengl-3.4.1.jar:$M2/org/joml/joml/1.10.7/joml-1.10.7.jar" \
  com.mojang.rubydung.RubyDung
