#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <output-path>" >&2
  exit 2
fi

output="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/manyue-anime4kcpp"
source_dir="$work/Anime4KCPP"
build_dir="$work/build"
source_commit="50c5d1b99965d804eeecfab9b48acfcaffddee3d"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
ndk=""

mkdir -p "$work" "$(dirname "$output")"
curl --fail --location --retry 5 --retry-all-errors \
  --output "$work/source.tar.gz" \
  "https://github.com/TianZerL/Anime4KCPP/archive/${source_commit}.tar.gz"
mkdir -p "$source_dir"
tar -xzf "$work/source.tar.gz" --strip-components=1 -C "$source_dir"
mkdir -p "$root/app/src/main/assets/licenses"
cp "$source_dir/LICENSE-MIT" "$root/app/src/main/assets/licenses/Anime4KCPP-LICENSE-MIT.txt"

if [[ -d "$sdk/ndk" ]]; then
  ndk="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d -print | sort -V | tail -n 1)"
fi
if [[ -z "$ndk" || ! -s "$ndk/build/cmake/android.toolchain.cmake" ]]; then
  sdkmanager="$(find "$sdk/cmdline-tools" -type f -name sdkmanager -print -quit 2>/dev/null || true)"
  if [[ -z "$sdkmanager" ]]; then
    echo "Android NDK not found and sdkmanager is unavailable under $sdk" >&2
    exit 1
  fi
  "$sdkmanager" --sdk_root="$sdk" --install "ndk;27.2.12479018"
  ndk="$sdk/ndk/27.2.12479018"
fi
test -s "$ndk/build/cmake/android.toolchain.cmake"

cmake -S "$source_dir" -B "$build_dir" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static \
  -DAC_BUILD_CLI=ON \
  -DAC_BUILD_GUI=OFF \
  -DAC_BUILD_VIDEO=OFF \
  -DAC_CORE_WITH_NEON=ON \
  -DAC_CORE_WITH_OPENCL=OFF \
  -DAC_CORE_WITH_CUDA=OFF \
  -DAC_CORE_ENABLE_FAST_MATH=ON
cmake --build "$build_dir" --target ac_cli --config Release --parallel 2

binary="$build_dir/bin/ac_cli"
test -s "$binary"
llvm_readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$llvm_readelf"
machine="$("$llvm_readelf" -h "$binary" | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
[[ "$machine" == *AArch64* ]] || { echo "Anime4KCPP worker is not ARM64: $machine" >&2; exit 1; }
chmod 755 "$binary"
cp "$binary" "$output"
sha256sum "$output"
echo "Anime4KCPP source=$source_commit ABI=arm64-v8a backend=CPU model=ACNetB4"
