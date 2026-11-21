#!/data/data/com.termux/files/usr/bin/sh
# FFlag Studio: javac -> d8 -> aapt2 -> zip dex + Rust .so -> apksigner
set -e
cd "$(dirname "$0")"

T="$HOME/apkbuild/tools"
SZ="$HOME/apkbuild/shizuku"
CORE="$HOME/.cache/fflagcore/release/libfflagcore.so"
CP="$T/android.jar:$SZ/api/classes.jar:$SZ/provider/classes.jar:$SZ/aidl/classes.jar"

rm -rf out
mkdir -p out/classes out/dex out/lib/arm64-v8a out/gen

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

cp "$CORE" out/lib/arm64-v8a/libfflagcore.so
(cd out && zip -q base.apk lib/arm64-v8a/libfflagcore.so)

java -jar "$T/apksigner.jar" sign \
	--ks "$HOME/apkbuild/debug.keystore" --ks-pass pass:android --key-pass pass:android \
	--min-sdk-version 26 --out out/app.apk out/base.apk

ls -lh out/app.apk
