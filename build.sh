#!/data/data/com.termux/files/usr/bin/bash
# ImgX 解包助手 — APK 构建脚本(无 Gradle, aapt2 + javac + d8 + apksigner)
set -e
cd "$(dirname "$0")"

export PATH="$PREFIX/bin:$PATH"
ANDROID_JAR="../build/tools/platform-34/android-34/android.jar"
OUT="build/apk"
GEN="build/gen"
CLS="build/classes-apk"
DEX="build/dex"
KSTORE="build/dna.keystore"
KSPASS="dna123456"
NATIVELIB="build/nativelib"

rm -rf "$OUT" "$GEN" "$CLS" "$DEX" "$NATIVELIB"
mkdir -p "$OUT" "$GEN" "$CLS" "$DEX"

echo "== [1/7] aapt2 compile resources =="
aapt2 compile --dir res -o "$OUT/res.zip"

MANIFEST="AndroidManifest.xml"
if [ -n "$DEBUG_BUILD" ]; then
    sed 's|<application|<application android:debuggable="true"|' AndroidManifest.xml > "$OUT/AndroidManifest.debug.xml"
    MANIFEST="$OUT/AndroidManifest.debug.xml"
fi

echo "== [2/7] aapt2 link =="
aapt2 link -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --java "$GEN" \
    --min-sdk-version 21 --target-sdk-version 27 \
    -A assets \
    "$OUT/res.zip"

echo "== [3/7] javac =="
find "$GEN" -name "*.java" > "$OUT/gen-srcs.txt"
find java -name "*.java" > "$OUT/app-srcs.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "$CLS" \
    @"$OUT/gen-srcs.txt" @"$OUT/app-srcs.txt"

echo "== [4/7] d8 dex =="
find "$CLS" -name "*.class" > "$OUT/classes.txt"
d8 --release --lib "$ANDROID_JAR" --min-api 26 \
    --output "$DEX" @"$OUT/classes.txt"

echo "== [5/7] build native libs (C liquid glass) =="
if [ -d csrc ] && [ -f csrc/liquidglass.c ]; then
    # 64 位(arm64) + 32 位(armv7) 都编一份: 只有 arm64 时, 32 位设备会因"无匹配ABI"装不上
    mkdir -p "$NATIVELIB/arm64-v8a" "$NATIVELIB/armeabi-v7a"
    clang -O3 -ffast-math -std=c99 -fPIC -shared -DANDROID \
        -Icsrc csrc/liquidglass.c csrc/liquidglass_jni.c \
        -o "$NATIVELIB/arm64-v8a/libliquidglass_jni.so" -lm
    echo "  arm64-v8a:   $(stat -c%s "$NATIVELIB/arm64-v8a/libliquidglass_jni.so") 字节"
    if clang -target armv7a-linux-androideabi24 -O3 -ffast-math -std=c99 -fPIC -shared -DANDROID \
        -Icsrc csrc/liquidglass.c csrc/liquidglass_jni.c \
        -o "$NATIVELIB/armeabi-v7a/libliquidglass_jni.so" -lm 2>/dev/null; then
        echo "  armeabi-v7a: $(stat -c%s "$NATIVELIB/armeabi-v7a/libliquidglass_jni.so") 字节"
    else
        rmdir "$NATIVELIB/armeabi-v7a" 2>/dev/null || true
        echo "  (32 位工具链不可用, 仅 arm64)"
    fi
else
    echo "  跳过(未找到 csrc/liquidglass.c)"
fi

echo "== [6/7] pack dex + native libs into apk =="
python3 - "$OUT" "$DEX" "$NATIVELIB" <<'PY'
import sys, zipfile, os, struct

out, dex, nat = sys.argv[1], sys.argv[2], sys.argv[3]
base = out + '/base.apk'
unsigned = out + '/unsigned.apk'
ALIGN = 4096        # 原生库页对齐(等效 zipalign -p 4)

libs = []
if os.path.isdir(nat):
    for root, dirs, files in os.walk(nat):
        for n in sorted(files):
            fp = os.path.join(root, n)
            arc = 'lib/' + os.path.relpath(fp, nat).replace(os.sep, '/')
            libs.append((arc, open(fp, 'rb').read()))

with open(dex + '/classes.dex', 'rb') as f:
    dex_data = f.read()


def measure(path):
    """返回 {档案名: 数据起始偏移} —— 直接从写好的 zip 读, 不依赖 tell()"""
    raw = open(path, 'rb').read()
    z = zipfile.ZipFile(path)
    res = {}
    for n in z.namelist():
        i = z.getinfo(n)
        off = i.header_offset
        nl, el = struct.unpack('<HH', raw[off + 26:off + 30])
        res[n] = off + 30 + nl + el
    return res


def pad_ok(p):
    """padding 为 0 或 >=4(extra 字段有效)"""
    if p and p < 4:
        p += ALIGN
    return p


def build(pads):
    """pads: {档案名: 实际padding字节数}; 缺省用最小字节对齐 padding"""
    with zipfile.ZipFile(base, 'r') as zin, \
         zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            zi = zipfile.ZipInfo(info.filename)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.date_time = info.date_time
            zout.writestr(zi, zin.read(info.filename))
        d = zipfile.ZipInfo('classes.dex'); d.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(d, dex_data)
        for arc, data in libs:
            nm = arc.encode('utf-8')
            pad = pads.get(arc)
            if pad is None:
                pad = (ALIGN - (len(nm)) % ALIGN) % ALIGN
            pad = pad_ok(pad)
            zi = zipfile.ZipInfo(arc)
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            zi.extra = (struct.pack('<HH', 0xD935, pad - 4) + b'\x00' * (pad - 4)) if pad >= 4 else b''
            zout.writestr(zi, data)


# 迭代对齐: 每轮实测真实偏移后修正; 修正某个库会推移其后的库, 故循环至收敛
pads = {}
for arc, data in libs:
    nm = arc.encode('utf-8')
    pads[arc] = (ALIGN - (len(nm)) % ALIGN) % ALIGN

pos = {}
for _ in range(8):
    build(pads)
    pos = measure(unsigned)
    settled = True
    for arc, data in libs:
        need = (ALIGN - (pos[arc] % ALIGN)) % ALIGN
        if need:
            settled = False
            pads[arc] = pad_ok(pads[arc] + need)
    if settled:
        break

ok = True
for arc, data in libs:
    good = pos[arc] % ALIGN == 0
    ok = ok and good
    print('  + %s (STORED, %d 字节, 偏移=%d, 4K对齐=%s)'
          % (arc, len(data), pos[arc], 'OK' if good else 'FAIL'))
print('  原生库对齐:', '全部通过' if ok else '仍有偏差')
PY
echo "  (对齐由构建脚本内置实现, 无需 zipalign)" 

echo "== [7/7] sign =="
if [ ! -f "$KSTORE" ]; then
    keytool -genkeypair -keystore "$KSTORE" -alias dna -keyalg RSA -keysize 2048 \
        -validity 10950 -storepass "$KSPASS" -keypass "$KSPASS" \
        -dname "CN=ImgX, OU=ImgX, O=zhiyu, C=CN" 2>/dev/null
fi
apksigner sign --ks "$KSTORE" --ks-pass "pass:$KSPASS" \
    --out "$OUT/ImgX-v4.5.5.apk" "$OUT/unsigned.apk"

echo ""
echo "APK: $OUT/ImgX-v4.5.5.apk"
ls -lh "$OUT/ImgX-v4.5.5.apk"
