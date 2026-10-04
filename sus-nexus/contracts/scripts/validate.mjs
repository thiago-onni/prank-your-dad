// Valida os contratos: envelope + schemas de dados, exemplos, tópicos e OpenAPI.
// Uso: pnpm validate (ou node scripts/validate.mjs). Sai com código != 0 em falha.
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import YAML from "yaml";
import SwaggerParser from "@apidevtools/swagger-parser";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const eventsDir = join(root, "events");
let failures = 0;
const fail = (msg) => {
  failures++;
  console.error(`✗ ${msg}`);
};
const ok = (msg) => console.log(`✓ ${msg}`);

const walk = (dir) =>
  readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });

const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);

// 1. Compila todos os schemas
const schemaFiles = walk(eventsDir).filter((f) => f.endsWith(".schema.json"));
const schemas = new Map();
for (const file of schemaFiles) {
  try {
    const schema = JSON.parse(readFileSync(file, "utf8"));
    const validate = ajv.compile(schema);
    schemas.set(schema.$id, { file, validate, schema });
    ok(`schema ${relative(root, file)}`);
  } catch (e) {
    fail(`schema ${relative(root, file)}: ${e.message}`);
  }
}

// 2. Valida exemplos contra envelope e contra o schema de dados do tipo de evento
const envelope = [...schemas.values()].find((s) => s.file.endsWith("envelope.schema.json"));
const dataSchemaFor = (eventType) => {
  // sus.identity.citizen.created → events/identity/citizen.v1.schema.json
  // sus.task.created → events/task/task.v1.schema.json
  const parts = eventType.split(".");
  const domain = parts[1];
  const entity = parts.length === 4 ? parts[2] : parts[1];
  return [...schemas.values()].find((s) =>
    s.file.endsWith(join(domain, `${entity}.v1.schema.json`))
  );
};
const exampleFiles = walk(join(eventsDir, "examples")).filter((f) => f.endsWith(".json"));
for (const file of exampleFiles) {
  const example = JSON.parse(readFileSync(file, "utf8"));
  if (!envelope.validate(example)) {
    fail(`example ${relative(root, file)} (envelope): ${ajv.errorsText(envelope.validate.errors)}`);
    continue;
  }
  const ds = dataSchemaFor(example.event_type);
  if (!ds) {
    fail(`example ${relative(root, file)}: sem schema de dados para ${example.event_type}`);
    continue;
  }
  if (!ds.validate(example.data)) {
    fail(`example ${relative(root, file)} (data): ${ajv.errorsText(ds.validate.errors)}`);
    continue;
  }
  const forbidden = JSON.stringify(example).match(/"value"\s*:\s*"\d{11,15}"/);
  if (forbidden) fail(`example ${relative(root, file)}: identificador em claro no evento (KAF-009)`);
  else ok(`example ${relative(root, file)}`);
}

// 3. Tópicos: nomes válidos, chave e eventos declarados com schema de dados
const topics = YAML.parse(readFileSync(join(eventsDir, "topics.yaml"), "utf8"));
const topicRe = /^sus\.[a-z][a-z-]*(\.[a-z][a-z-]*)?\.v[0-9]+$/;
for (const t of topics.topics) {
  if (!topicRe.test(t.name)) fail(`topic ${t.name}: nome inválido`);
  else ok(`topic ${t.name}`);
  if (t.class === "domain" && !t.events?.length) fail(`topic ${t.name}: tópico de domínio sem eventos declarados`);
}

// 4. OpenAPI (core-municipal + ai-service, este último exportado por
//    `python -m sus_nexus_ai.export_openapi` no ai-service)
for (const name of ["core-municipal.yaml", "ai-service.yaml"]) {
  const file = join(root, "openapi", name);
  if (!existsSync(file)) {
    fail(`openapi ${name}: arquivo ausente`);
    continue;
  }
  try {
    const api = await SwaggerParser.validate(file);
    ok(`openapi ${name}: ${api.info.title} ${api.info.version} (${Object.keys(api.paths).length} paths)`);
  } catch (e) {
    fail(`openapi ${name}: ${e.message}`);
  }
}

if (failures) {
  console.error(`\n${failures} falha(s)`);
  process.exit(1);
}
console.log("\nContratos válidos.");
