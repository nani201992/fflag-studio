#!/data/data/com.termux/files/usr/bin/sh
# FFlag Studio: javac -> d8 -> aapt2 -> zip dex + Rust .so -> apksigner
set -e
cd "$(dirname "$0")"

T="$HOME/apkbuild/tools"
SZ="$HOME/apkbuild/shizuku"
CACHE="$HOME/.cache/fflagcore"
# Rust cdylib per ABI. arm64 is required; the other three make the APK universal.
ABIS="arm64-v8a:aarch64-linux-android armeabi-v7a:armv7-linux-androideabi x86_64:x86_64-linux-android x86:i686-linux-android"
CP="$T/android.jar:$SZ/api/classes.jar:$SZ/provider/classes.jar:$SZ/aidl/classes.jar"

rm -rf out
mkdir -p out/classes out/dex out/gen

# resources first: aapt2 emits R.java, which javac needs
aapt2 compile --dir res -o out/res.zip
aapt2 link -o out/base.apk --manifest AndroidManifest.xml -I "$T/android.jar" \
	--java out/gen out/res.zip

javac -source 8 -target 8 -nowarn -classpath "$CP" \
	-d out/classes $(find src out/gen -name '*.java')

java -cp "$T/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$T/android.jar" \
	--output out/dex $(find out/classes -name '*.class') \
	"$SZ/api/classes.jar" "$SZ/provider/classes.jar" "$SZ/aidl/classes.jar"
(cd out/dex && zip -q ../base.apk classes.dex)

built=0
for pair in $ABIS; do
	abi="${pair%%:*}"; triple="${pair##*:}"
	so="$CACHE/$triple/release/libfflagcore.so"
	[ -f "$so" ] || so="$CACHE/release/libfflagcore.so"   # legacy host build
	[ -f "$so" ] || continue
	[ "$abi" = "arm64-v8a" ] && built=1
	mkdir -p "out/lib/$abi"
	cp "$so" "out/lib/$abi/libfflagcore.so"
	(cd out && zip -q base.apk "lib/$abi/libfflagcore.so")
done
[ "$built" = 1 ] || { echo "no arm64 libfflagcore.so — build it first (see README)"; exit 1; }

java -jar "$T/apksigner.jar" sign \
	--ks "$HOME/apkbuild/debug.keystore" --ks-pass pass:android --key-pass pass:android \
	--min-sdk-version 26 --out out/app.apk out/base.apk

ls -lh out/app.apk
