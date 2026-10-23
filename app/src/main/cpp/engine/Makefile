TARGET = ciadpi

CPPFLAGS = -D_DEFAULT_SOURCE
CFLAGS += -I. -std=gnu99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic

HEADERS = conev.h desync.h error.h extend.h kavl.h mpool.h packets.h params.h proxy.h tpws.h udphost.h
SRC = packets.c main.c conev.c proxy.c desync.c mpool.c extend.c tpws.c udphost.c

OBJ = $(SRC:.c=.o)

PREFIX := /usr/local
INSTALL_DIR := $(DESTDIR)$(PREFIX)/bin/

all: $(TARGET)

$(TARGET): $(OBJ)
	$(CC) -o $(TARGET) $(OBJ) $(LDFLAGS) -lpthread

$(OBJ): $(HEADERS)
.c.o:
	$(CC) $(CPPFLAGS) $(CFLAGS) -c $<

clean:
	rm -f $(TARGET) $(OBJ)

install: $(TARGET)
	mkdir -p $(INSTALL_DIR)
	install -m 755 $(TARGET) $(INSTALL_DIR)
