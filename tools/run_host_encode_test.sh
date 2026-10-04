#!/usr/bin/env bash
# 主机侧自检：编译 libogg + libvorbis + 测试程序，生成一段 3 声道 Ogg，
# 然后校验 ANDROID_HAPTIC=1 是否为独立完整的一条 comment。
#
# 用法:  bash tools/run_host_encode_test.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
BUILD="$(mktemp -d)"
OGG="$CPP/third_party/ogg"
VORBIS="$CPP/third_party/vorbis"

SOURCES=(
  "$OGG/src/bitwise.c"
  "$OGG/src/framing.c"
)
# 与 CMakeLists.txt 相同的裁剪：剔除带 main() 的调试程序与解码用的 vorbisfile
for src in "$VORBIS"/lib/*.c; do
  case "$(basename "$src")" in
    psytune.c|barkmel.c|tone.c|vorbisfile.c) continue ;;
  esac
  SOURCES+=("$src")
done

echo "==> 编译主机侧 libogg + libvorbis（$BUILD）"
cd "$BUILD"
gcc -O2 -w -I"$OGG/include" -I"$VORBIS/include" -I"$VORBIS/include/vorbis" -I"$VORBIS/lib" \
    -c "${SOURCES[@]}" -lm

echo "==> 编译测试程序"
gcc -O2 -w -I"$OGG/include" -I"$VORBIS/include" -I"$VORBIS/include/vorbis" -I"$VORBIS/lib" \
    "$ROOT/tools/host_encode_test.c" ./*.o -lm -o "$BUILD/host_encode_test"

echo "==> 生成测试分片"
"$BUILD/host_encode_test" "$BUILD/host_test.ogg"

echo "==> 校验 vorbis comment"
python3 "$ROOT/tools/verify_ogg_comment.py" "$BUILD/host_test.ogg"

echo "==> 分片大小: $(stat -c%s "$BUILD/host_test.ogg") 字节（500ms / 3 声道 / quality 0.5）"
echo "==> 临时目录保留在 $BUILD（可自行删除）"
