#!/bin/sh
# Builds the libFuzzer targets for the engine's parsers (clang) and runs each for a while.
#   run.sh [seconds per target, default 60] [target ...]
# Crashes land in $FUZZ_OUT (default: a temporary directory), named crash-<target>-*.
set -e
here=$(cd "$(dirname "$0")" && pwd)
root="$here/../../.."
engine="$root/src/main/cpp/engine"
seeds="$root/src/main/assets/fake"
secs=${1:-60}
[ $# -gt 0 ] && shift
targets=${*:-"fuzz_tls fuzz_http fuzz_quic_dns"}
out=${FUZZ_OUT:-$(mktemp -d)}
CC=${CC:-clang}
flags="-O1 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer -std=gnu99 -w"

mkdir -p "$out/obj"
for src in "$engine"/*.c; do
    # The engine's main() is renamed: libFuzzer brings its own.
    $CC $flags -fsanitize=fuzzer-no-link -Dmain=engine_main -I"$engine" -c "$src" \
        -o "$out/obj/$(basename "$src" .c).o"
done

for t in $targets; do
    objs=$(ls "$out"/obj/*.o)
    # The QUIC/DNS target includes udphost.c itself (for its static crypto).
    [ "$t" = fuzz_quic_dns ] && objs=$(ls "$out"/obj/*.o | grep -v '/udphost\.o$')
    $CC $flags -fsanitize=fuzzer -I"$engine" "$here/$t.c" $objs -o "$out/$t" -lpthread
    corpus="$out/corpus-$t"
    mkdir -p "$corpus"
    case $t in
        fuzz_tls) cp "$seeds"/tls_clienthello_*.bin "$corpus"/ ;;
        fuzz_http) cp "$seeds"/http_*.bin "$corpus"/ ;;
        fuzz_quic_dns) cp "$seeds"/quic_initial_*.bin "$seeds"/dns.bin "$corpus"/ ;;
    esac
    echo "== $t (${secs}s)"
    "$out/$t" -max_total_time="$secs" -max_len=4096 -artifact_prefix="$out/crash-$t-" \
        -print_final_stats=1 "$corpus" 2>&1 | grep -E 'ERROR|SUMMARY|stat::number_of_executed_units|crash-|Done' || true
done
ls "$out"/crash-* 2>/dev/null && { echo "crashes found in $out"; exit 1; }
echo "no crashes ($out)"
