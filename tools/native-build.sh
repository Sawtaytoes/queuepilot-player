#!/usr/bin/env bash
# Runs inside the bounded build container. Call through tools/build.sh.
set -euo pipefail
native_cache=/cache/native-ass
mkdir -p "$native_cache" /source/android/app/build/native-libs
fingerprint=$(cat /source/tools/native-build.sh /source/tools/native-sources.json /source/android/app/src/main/cpp/ass_bridge.c | sha256sum | cut -d' ' -f1)
if [[ -f "$native_cache/$fingerprint/complete" ]]; then
  cp -a "$native_cache/$fingerprint/libs/." /source/android/app/build/native-libs/
  mkdir -p /source/android/app/build/native-assets/licenses
  cp -a "$native_cache/$fingerprint/licenses/." /source/android/app/build/native-assets/licenses/
  exit 0
fi
apt-get update -qq
apt-get install -y --no-install-recommends build-essential pkg-config python3-pip python3-venv ninja-build
if [[ ! -x "$native_cache/venv/bin/meson" ]]; then
  python3 -m venv "$native_cache/venv"
  "$native_cache/venv/bin/pip" install meson==1.8.3
fi
export PATH="$native_cache/venv/bin:$PATH"
ndk_version=28.2.13676358
if [[ ! -d "/cache/sdk/ndk/$ndk_version" ]]; then
  mkdir -p /cache/sdk/licenses
  cp -a /opt/android-sdk/licenses/. /cache/sdk/licenses/
  sdkmanager --sdk_root=/cache/sdk "ndk;$ndk_version"
fi
export NDK_BIN="/cache/sdk/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin"
python3 - <<'PY'
import json, pathlib, urllib.request, tarfile
root=pathlib.Path('/cache/native-ass/sources'); root.mkdir(parents=True,exist_ok=True)
for package in json.load(open('/source/tools/native-sources.json')):
    destination=root/package['name']
    if destination.exists(): continue
    archive=root/(package['name']+'.tar.gz')
    urllib.request.urlretrieve('https://codeload.github.com/'+package['repository']+'/tar.gz/'+package['commit'],archive)
    with tarfile.open(archive) as source:
        members=source.getmembers(); prefix=members[0].name
        # Upstream source is pinned to its immutable commit. Refuse unsafe tar paths.
        for member in members:
            relative=pathlib.PurePosixPath(member.name)
            if relative.is_absolute() or '..' in relative.parts or member.issym() or member.islnk():
                if member.issym() or member.islnk(): continue
                raise ValueError('Unsafe archive path')
            source.extract(member,root)
        (root/prefix).rename(destination)
PY
for abi in arm64-v8a armeabi-v7a x86; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android; compiler=aarch64-linux-android30; family=aarch64; cpu=aarch64;;
    armeabi-v7a) triple=arm-linux-androideabi; compiler=armv7a-linux-androideabi30; family=arm; cpu=armv7a;;
    x86) triple=i686-linux-android; compiler=i686-linux-android30; family=x86; cpu=i686;;
  esac
  work="$native_cache/$fingerprint/$abi"
  prefix="$work/install"
  mkdir -p "$work" "$prefix" "$native_cache/$fingerprint/libs/$abi"
  cat > "$work/cross.ini" <<EOF
[binaries]
c = '$NDK_BIN/$compiler-clang'
cpp = '$NDK_BIN/$compiler-clang++'
ar = '$NDK_BIN/llvm-ar'
strip = '$NDK_BIN/llvm-strip'
pkg-config = '/usr/bin/pkg-config'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = '$prefix/lib/pkgconfig'
[host_machine]
system = 'android'
cpu_family = '$family'
cpu = '$cpu'
endian = 'little'
EOF
  build_library() {
    local library=$1; shift
    if [[ ! -d "$work/$library" ]]; then
      meson setup "$work/$library" "$native_cache/sources/$library" --cross-file "$work/cross.ini" \
        --prefix "$prefix" --libdir lib --buildtype release --default-library static -Db_staticpic=true "$@"
    fi
    meson compile -C "$work/$library" -j 2
    meson install -C "$work/$library"
  }
  build_library freetype -Dharfbuzz=disabled -Dzlib=disabled -Dbzip2=disabled -Dpng=disabled -Dbrotli=disabled -Dtests=disabled
  build_library fribidi -Ddocs=false -Dtests=false -Dbin=false
  build_library harfbuzz -Dtests=disabled -Dutilities=disabled -Ddocs=disabled -Dcairo=disabled -Dglib=disabled -Dgobject=disabled -Dfreetype=disabled -Dicu=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled -Dsubset=disabled -Dchafa=disabled -Dpng=disabled -Dzlib=disabled
  build_library libass -Dtest=disabled -Dcompare=disabled -Dprofile=disabled -Dfontconfig=disabled -Dcoretext=disabled -Ddirectwrite=disabled -Drequire-system-font-provider=false -Dlibunibreak=disabled -Dasm=auto
  "$NDK_BIN/$compiler-clang" -std=c11 -O2 -fPIC -I"$prefix/include" -c /source/android/app/src/main/cpp/ass_bridge.c -o "$work/ass_bridge.o"
  "$NDK_BIN/$compiler-clang++" -shared -Wl,-z,max-page-size=16384 "$work/ass_bridge.o" \
    -Wl,--start-group "$prefix/lib/libass.a" "$prefix/lib/libfreetype.a" "$prefix/lib/libfribidi.a" "$prefix/lib/libharfbuzz.a" -Wl,--end-group \
    -static-libstdc++ -lm -ljnigraphics -llog -o "$native_cache/$fingerprint/libs/$abi/libqueuepilot_ass.so"
  "$NDK_BIN/llvm-strip" --strip-unneeded "$native_cache/$fingerprint/libs/$abi/libqueuepilot_ass.so"
done
mkdir -p "$native_cache/$fingerprint/licenses" /source/android/app/build/native-assets/licenses
for package in libass freetype fribidi harfbuzz; do
  for license in COPYING LICENSE LICENSE.TXT docs/FTL.TXT; do
    if [[ -f "$native_cache/sources/$package/$license" ]]; then
      cp "$native_cache/sources/$package/$license" "$native_cache/$fingerprint/licenses/$package-$(basename "$license").txt"
    fi
  done
done
cp -a "$native_cache/$fingerprint/libs/." /source/android/app/build/native-libs/
cp -a "$native_cache/$fingerprint/licenses/." /source/android/app/build/native-assets/licenses/
touch "$native_cache/$fingerprint/complete"
