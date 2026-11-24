#!/data/data/com.termux/files/usr/bin/sh
# Builds libfflagcore.so for every ABI the APK ships, into ~/.cache/fflagcore/<triple>/release/.
# Termux deps: pkg install rust rust-src ndk-multilib
# The armv7/x86/x86_64 std libraries are not shipped prebuilt, so they are compiled from source
# (-Z build-std); the rust-src package provides that source. arm64 uses the prebuilt std.
set -e
cd "$(dirname "$0")"

P="${PREFIX:-/data/data/com.termux/files/usr}"
M="$P/opt/ndk-multilib"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$HOME/.cache/fflagcore}"
# -Z on a stable toolchain: cargo/rustc treat this as nightly for the build only
export RUSTC_BOOTSTRAP=1
export __CARGO_TEST_CHANNEL_OVERRIDE_DO_NOT_USE_THIS=nightly

# build <rust-triple> <clang target> <sysroot dir> <prebuilt-std: yes|no>
build() {
	upper=$(echo "$1" | tr 'a-z-' 'A-Z_')
	eval "export CARGO_TARGET_${upper}_LINKER=clang"
	eval "export CARGO_TARGET_${upper}_RUSTFLAGS=\"-C link-arg=--target=$2 -C link-arg=-L$M/$3/lib -C link-arg=-L$P/$3/lib\""
	if [ "$4" = yes ]; then
		cargo build --release --target "$1"
	else
		cargo build --release -Z build-std=std,panic_abort --target "$1"
	fi
	echo "-> $CARGO_TARGET_DIR/$1/release/libfflagcore.so"
}

build aarch64-linux-android   aarch64-linux-android26   aarch64-linux-android   yes
build armv7-linux-androideabi armv7a-linux-androideabi26 arm-linux-androideabi   no
build x86_64-linux-android    x86_64-linux-android26    x86_64-linux-android    no
build i686-linux-android      i686-linux-android26      i686-linux-android      no
