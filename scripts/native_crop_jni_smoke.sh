#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_root="${1:-project}"
if [[ "$source_root" != /* ]]; then
  source_root="$repo_root/$source_root"
fi
crop_dir="$source_root/reader-ssiv/src/main/cpp/crop"
build_dir="$repo_root/.build/native-crop-jni-smoke"
classes_dir="$build_dir/classes"

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME must point to a JDK (the smoke test needs javac, java, and JNI headers)." >&2
  exit 2
fi
if [[ ! -x "$JAVA_HOME/bin/javac" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME must contain executable bin/javac and bin/java: $JAVA_HOME" >&2
  exit 2
fi
if [[ ! -f "$JAVA_HOME/include/jni.h" || ! -f "$JAVA_HOME/include/linux/jni_md.h" ]]; then
  echo "JAVA_HOME is missing Linux JNI headers: $JAVA_HOME/include" >&2
  exit 2
fi
if ! command -v g++ >/dev/null 2>&1; then
  echo "g++ is required to build the host JNI library." >&2
  exit 2
fi
for source in crop.cpp borders.cpp; do
  if [[ ! -f "$crop_dir/$source" ]]; then
    echo "Expected native crop source not found: $crop_dir/$source" >&2
    exit 2
  fi
done

mkdir -p "$classes_dir"
g++ -std=c++17 -shared -fPIC -O2 \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  -I"$crop_dir" \
  "$crop_dir/crop.cpp" \
  "$crop_dir/borders.cpp" \
  -o "$build_dir/libssiv_crop.so"

"$JAVA_HOME/bin/javac" -d "$classes_dir" \
  "$repo_root/scripts/native_crop_jni/CropBorders.java"
"$JAVA_HOME/bin/java" \
  -Djava.library.path="$build_dir" \
  -cp "$classes_dir" \
  com.davemorrissey.labs.subscaleview.CropBorders
