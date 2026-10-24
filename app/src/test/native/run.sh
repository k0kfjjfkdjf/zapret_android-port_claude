#!/bin/sh
# Builds the engine for the host and runs the native tests.
set -e
here=$(cd "$(dirname "$0")" && pwd)
root="$here/../../.."
engine="$root/src/main/cpp/engine"
out=$(mktemp -d)
flags="${CFLAGS:--O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer} -std=gnu99 -Wall -Wextra -Wno-unused -Wno-unused-parameter -Wno-sign-compare"
for src in "$engine"/*.c; do
    # The engine's main() is renamed so that the test can define its own.
    cc $flags -Dmain=engine_main -I"$engine" -c "$src" -o "$out/$(basename "$src" .c).o"
done
cc $flags -I"$engine" -c "$here/test_tpws.c" -o "$out/test_tpws.o"
cc $flags "$out"/*.o -o "$out/test_tpws" -lpthread
"$out/test_tpws" "$root/src/main/assets/fake/tls_clienthello_www_google_com.bin" "$root/src/main/assets/fake"
python3 "$here/check_strategies.py" "$out/test_tpws" "$root"
# The engine as a program (its own main), for the tests that talk to it over sockets.
cc $flags -I"$engine" "$engine"/*.c -o "$out/ciadpi" -lpthread
python3 "$here/udp_assoc_test.py" "$out/ciadpi"
python3 "$here/udp_assoc_test.py" "$out/ciadpi" --udp-fake 1 --udp-desync 1:3 --conn-log
python3 "$here/auto_cache_test.py" "$out/ciadpi"
python3 "$here/udp_auto_test.py" "$out/ciadpi"
python3 "$here/quic_split_test.py" "$out/ciadpi" "$root/src/main/assets/fake"
