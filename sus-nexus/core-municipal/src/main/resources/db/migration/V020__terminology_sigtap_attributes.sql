-- =====================================================================
-- V020 — terminology: atributos SIGTAP usados pela pré-auditoria de produção
--        (PRO-002/003) nos códigos de exemplo + códigos APAC/AIH e CBOs
--        adicionais. Atributos (jsonb, por competência):
--          cbos[]        CBOs permitidos (vazio/ausente = sem restrição)
--          sexo          F | M | I (ambos)
--          idade_min/max anos completos na data do atendimento
--          qt_maxima     quantidade máxima por registro individualizado
--          complexidade  AB | MC | AC
--          instrumentos  BPA-C | BPA-I | APAC | AIH
--          valor         valor unitário de referência (R$) — estimativa de faturamento
--        Valores ilustrativos (seed de desenvolvimento); a carga oficial vem do
--        conector SIGTAP por competência (POST /terminology/SIGTAP/codes/upsert).
-- =====================================================================
UPDATE terminology.code SET attributes = attributes || '{"cbos":["225125","225142","225170"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":1,"complexidade":"AB","instrumentos":["BPA-I"],"valor":10.00}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0301010064';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["223505","223565","251510","223605"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":1,"complexidade":"AB","instrumentos":["BPA-I"],"valor":6.30}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0301010030';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["225125","225250","225124","225109"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":1,"complexidade":"MC","instrumentos":["BPA-I"],"valor":10.00}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0301010048';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["223505","223565","225142","515105","322205"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":9999,"complexidade":"AB","instrumentos":["BPA-C"],"valor":0.00}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0101010010';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["225125","225142","223505","223565"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":1,"complexidade":"AB","instrumentos":["BPA-C","BPA-I"],"valor":0.00}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0301100012';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["221205","223415"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":1,"complexidade":"MC","instrumentos":["BPA-C"],"valor":4.11}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0202010503';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["221205","223415"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":1,"complexidade":"MC","instrumentos":["BPA-C"],"valor":1.85}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0202010473';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["225320"],"sexo":"F","idade_min":35,"idade_max":130,"qt_maxima":1,"complexidade":"MC","instrumentos":["BPA-I"],"valor":45.00}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0204030153';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["225305","221205","223415"],"sexo":"F","idade_min":12,"idade_max":130,"qt_maxima":1,"complexidade":"MC","instrumentos":["BPA-C","BPA-I"],"valor":6.97}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0203010086';
UPDATE terminology.code SET attributes = attributes || '{"cbos":["515105","322205","322245"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":9999,"complexidade":"AB","instrumentos":["BPA-C"],"valor":0.00}'::jsonb
 WHERE system = 'SIGTAP' AND code = '0301010129';

INSERT INTO terminology.code (system, code, display, competence_from, competence_to, attributes) VALUES
  ('SIGTAP', '0303140151', 'Tratamento de pneumonias ou influenza (gripe)', '202401', NULL,
   '{"group":"03","subgroup":"03","complexity":"MC","financing":"MAC","cbos":["225125","225124"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":1,"complexidade":"MC","instrumentos":["AIH"],"valor":563.17}'),
  ('SIGTAP', '0305010107', 'Hemodiálise (máximo 3 sessões por semana)', '202401', NULL,
   '{"group":"03","subgroup":"05","complexity":"AC","financing":"FAEC","cbos":["225109","225125"],"sexo":"I","idade_min":0,"idade_max":130,"qt_maxima":14,"complexidade":"AC","instrumentos":["APAC"],"valor":240.97}'),

  ('CBO', '225170', 'Médico generalista', '200201', NULL, '{"family":"2251"}'),
  ('CBO', '225124', 'Médico pediatra', '200201', NULL, '{"family":"2251"}'),
  ('CBO', '225109', 'Médico nefrologista', '200201', NULL, '{"family":"2251"}'),
  ('CBO', '225250', 'Médico ginecologista e obstetra', '200201', NULL, '{"family":"2252"}'),
  ('CBO', '225305', 'Médico citopatologista', '200201', NULL, '{"family":"2253"}'),
  ('CBO', '225320', 'Médico em radiologia e diagnóstico por imagem', '200201', NULL, '{"family":"2253"}'),
  ('CBO', '223565', 'Enfermeiro da estratégia de saúde da família', '200201', NULL, '{"family":"2235"}'),
  ('CBO', '223415', 'Farmacêutico analista clínico', '200201', NULL, '{"family":"2234"}'),
  ('CBO', '221205', 'Biomédico', '200201', NULL, '{"family":"2212"}'),
  ('CBO', '223605', 'Fisioterapeuta geral', '200201', NULL, '{"family":"2236"}'),
  ('CBO', '251510', 'Psicólogo clínico', '200201', NULL, '{"family":"2515"}'),
  ('CBO', '322245', 'Técnico de enfermagem da estratégia de saúde da família', '200201', NULL, '{"family":"3222"}');
