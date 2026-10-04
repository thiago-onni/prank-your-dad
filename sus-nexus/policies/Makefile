# SUS Nexus — políticas OPA (Rego v1)
#
# Alvos:
#   make fmt     formata todos os .rego (opa fmt -w)
#   make check   opa fmt --diff (falha se houver diff) + opa check --strict
#   make test    opa test -v (unitários de todas as políticas)
#   make bundle  gera bundle/bundle.tar.gz com .manifest (roots: sus, data)
#   make eval    avalia a decisão para examples/aps_read_team.json
#   make partial exemplo de partial evaluation para geração de filtros
#
# Observação: todos os comandos carregam este diretório como raiz (`.`), de
# modo que os arquivos de data/ ficam montados em `data.data.*` — igual ao
# bundle (roots sus + data) servido pelo OPA em produção.

OPA      ?= opa
ROOT     := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
VERSION  ?= $(shell cat $(ROOT)VERSION 2>/dev/null || echo 0.0.0-dev)
REVISION ?= $(VERSION)+$(shell git -C $(ROOT) rev-parse --short HEAD 2>/dev/null || echo nogit)
EXAMPLE  ?= examples/aps_read_team.json
# Diretórios que não são políticas nem dados (não devem ser montados em `data`).
IGNORE   := --ignore examples --ignore bundle

.PHONY: all fmt fmt-check check test test-coverage bundle eval partial clean

all: check test

fmt:
	$(OPA) fmt -w sus

fmt-check:
	$(OPA) fmt --diff --fail sus

check: fmt-check
	$(OPA) check --strict sus data

test: check
	$(OPA) test -v $(IGNORE) .

test-coverage:
	$(OPA) test --coverage --format=json $(IGNORE) . | $(OPA) eval --stdin-input --format pretty 'input.coverage'

bundle: check
	REVISION="$(REVISION)" OPA="$(OPA)" ./bundle/build.sh

eval:
	$(OPA) eval -d . $(IGNORE) -i $(EXAMPLE) --format pretty 'data.sus.authz.decision'

partial:
	$(OPA) eval -d . $(IGNORE) -i $(EXAMPLE) --format pretty --partial --unknowns input.resource 'data.sus.authz.filter'

clean:
	rm -f bundle/bundle.tar.gz
	rm -rf bundle/.stage
