APP_OPTIM := release
APP_MODULES := hev-socks5-tunnel
APP_PLATFORM := android-24
APP_CFLAGS := -O3 -DPKGNAME=io/github/zapretdroid/core -DCLSNAME=TProxyService
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
APP_LDFLAGS := -Wl,-z,max-page-size=16384
NDK_TOOLCHAIN_VERSION := clang
