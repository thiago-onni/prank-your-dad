# ADR-0003: FHIR Gateway próprio usando a implementação de referência HL7 apenas como biblioteca

- **Status:** Aprovado para a Fase 1
- **Data:** 2026-10-04
- **Decisores:** Conselho de arquitetura SUS Nexus

## Contexto

A especificação veda o servidor HAPI FHIR, mas recomenda reutilizar artefatos oficiais. As bibliotecas Java mais maduras para modelos R4, parser, FHIRPath e validação ficam em `org.hl7.fhir.core` (implementação de referência mantida pela HL7, publicada sob o grupo Maven `ca.uhn.hapi.fhir`), separadas do servidor HAPI.

## Decisão

Usar `org.hl7.fhir.r4` (modelos, JsonParser, FHIRPath) e `org.hl7.fhir.validation` **como bibliotecas**, encapsuladas por interfaces próprias (`FhirCodec`, `FhirValidator`). Proibido: `hapi-fhir-server`, `hapi-fhir-jpaserver-*`, `RestfulServer`, interceptors HAPI. API REST, persistência (JSONB + tabelas de índice), segurança, mascaramento, auditoria e `CapabilityStatement` são próprios. O `CapabilityStatement` é gerado a partir de um registro em código do que está implementado.

## Consequências

- Conformidade de serialização/validação com custo baixo.
- Controle total de API, persistência e políticas.
- Dependência de atualização dos pacotes de IG (br-core/RNDS) fixados no build.
- Escopo de recursos cresce incrementalmente (FHIR-1 a FHIR-4).
