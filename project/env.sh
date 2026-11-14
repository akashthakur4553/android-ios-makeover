#!/bin/bash
# Finds a JDK 17+ and the Android SDK on macOS / Linux / Windows (Git Bash, WSL). Sourced by the deploy scripts.
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
  for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           /opt/android-studio/jbr /usr/local/android-studio/jbr "$HOME/android-studio/jbr" "/c/Program Files/Android/Android Studio/jbr" \
           $(/usr/libexec/java_home -v 17+ 2>/dev/null) /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-17-openjdk* /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk; do
    [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && break
  done
fi
[ -x "${JAVA_HOME:-}/bin/java" ] || { echo "❌ JDK 17+ not found. Install Android Studio or a JDK 17+, then set JAVA_HOME." >&2; exit 1; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
for c in "$SDK" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/c/Users/$USER/AppData/Local/Android/Sdk"; do [ -d "$c/platform-tools" ] && SDK="$c" && break; done
[ -d "$SDK/platform-tools" ] || { echo "❌ Android SDK not found. Install Android Studio (it installs the SDK) or set ANDROID_HOME." >&2; exit 1; }
export ANDROID_HOME="$SDK"; export PATH="$SDK/platform-tools:$PATH"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
[ -d "$SDK/licenses" ] || echo "⚠️  Accept the Android SDK licences first (open Android Studio once, or run: sdkmanager --licenses)." >&2
