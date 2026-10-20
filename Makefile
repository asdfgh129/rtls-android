DATABASE_PATH ?= $(CURDIR)/server/data/rtls.db
SERVER_DIR := server
NPM := npm

.PHONY: install migrate migrate-down migrate-status db-reset db-shell test build typecheck

install:
	cd $(SERVER_DIR) && $(NPM) install

migrate:
	mkdir -p $(dir $(DATABASE_PATH))
	cd $(SERVER_DIR) && DATABASE_PATH="$(DATABASE_PATH)" $(NPM) run migrate -- up

migrate-down:
	cd $(SERVER_DIR) && DATABASE_PATH="$(DATABASE_PATH)" $(NPM) run migrate -- down

migrate-status:
	cd $(SERVER_DIR) && DATABASE_PATH="$(DATABASE_PATH)" $(NPM) run migrate -- status

db-reset:
	rm -f "$(DATABASE_PATH)" "$(DATABASE_PATH)-wal" "$(DATABASE_PATH)-shm"
	$(MAKE) migrate

db-shell:
	sqlite3 "$(DATABASE_PATH)"

test:
	cd $(SERVER_DIR) && $(NPM) test

build:
	cd $(SERVER_DIR) && $(NPM) run build

typecheck:
	cd $(SERVER_DIR) && $(NPM) run typecheck
