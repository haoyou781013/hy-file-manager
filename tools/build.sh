#!/bin/bash
# HY 文件管理构建脚本
WS=/sdcard/Android/haoyou781
PROJECT=$WS/HY文件管理
GRADLE=$WS/toolchain/gradle/gradle-8.1.1/bin/gradle
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$WS/toolchain/android-sdk
export GRADLE_USER_HOME=$WS/toolchain/gradle-home
export JAVA_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
cd "$PROJECT"
sh $GRADLE :app:assembleRelease --no-daemon "$@"
