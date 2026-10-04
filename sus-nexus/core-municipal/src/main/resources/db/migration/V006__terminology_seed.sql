-- =====================================================================
-- V006 — seed mínimo de terminologia (exemplo; carga completa virá dos
--        conectores de SIGTAP/CID/CIAP/CBO por competência).
-- =====================================================================
INSERT INTO terminology.code (system, code, display, competence_from, competence_to, attributes) VALUES
  ('SIGTAP', '0301010064', 'Consulta médica em atenção primária', '202401', NULL, '{"group":"03","subgroup":"01","complexity":"AB","financing":"PAB"}'),
  ('SIGTAP', '0301010030', 'Consulta de profissionais de nível superior na atenção primária (exceto médico)', '202401', NULL, '{"group":"03","subgroup":"01","complexity":"AB","financing":"PAB"}'),
  ('SIGTAP', '0301010048', 'Consulta médica em atenção especializada', '202401', NULL, '{"group":"03","subgroup":"01","complexity":"MC","financing":"MAC"}'),
  ('SIGTAP', '0101010010', 'Atividade educativa / orientação em grupo na atenção primária', '202401', NULL, '{"group":"01","subgroup":"01","complexity":"AB","financing":"PAB"}'),
  ('SIGTAP', '0301100012', 'Atendimento de urgência em atenção primária', '202401', NULL, '{"group":"03","subgroup":"01","complexity":"AB","financing":"PAB"}'),
  ('SIGTAP', '0202010503', 'Hemograma completo', '202401', NULL, '{"group":"02","subgroup":"02","complexity":"MC","financing":"MAC"}'),
  ('SIGTAP', '0202010473', 'Dosagem de glicose', '202401', NULL, '{"group":"02","subgroup":"02","complexity":"MC","financing":"MAC"}'),
  ('SIGTAP', '0204030153', 'Mamografia bilateral para rastreamento', '202401', NULL, '{"group":"02","subgroup":"04","complexity":"MC","financing":"MAC"}'),
  ('SIGTAP', '0203010086', 'Exame citopatológico cérvico-vaginal/microflora', '202401', NULL, '{"group":"02","subgroup":"03","complexity":"MC","financing":"MAC"}'),
  ('SIGTAP', '0301010129', 'Visita domiciliar por profissional de nível médio', '202401', NULL, '{"group":"03","subgroup":"01","complexity":"AB","financing":"PAB"}'),

  ('CID10', 'I10',   'Hipertensão essencial (primária)', '200001', NULL, '{"chapter":"IX"}'),
  ('CID10', 'E11',   'Diabetes mellitus não-insulino-dependente', '200001', NULL, '{"chapter":"IV"}'),
  ('CID10', 'J45',   'Asma', '200001', NULL, '{"chapter":"X"}'),
  ('CID10', 'F32',   'Episódios depressivos', '200001', NULL, '{"chapter":"V"}'),
  ('CID10', 'Z00.0', 'Exame médico geral', '200001', NULL, '{"chapter":"XXI"}'),
  ('CID10', 'A90',   'Dengue [dengue clássico]', '200001', NULL, '{"chapter":"I","notifiable":true}'),
  ('CID10', 'O80',   'Parto único espontâneo', '200001', NULL, '{"chapter":"XV"}'),
  ('CID10', 'N39.0', 'Infecção do trato urinário de localização não especificada', '200001', NULL, '{"chapter":"XIV"}'),
  ('CID10', 'J06.9', 'Infecção aguda das vias aéreas superiores não especificada', '200001', NULL, '{"chapter":"X"}'),
  ('CID10', 'K29.7', 'Gastrite não especificada', '200001', NULL, '{"chapter":"XI"}'),

  ('CIAP2', 'K86', 'Hipertensão sem complicações', '200001', NULL, '{"chapter":"K","component":"7"}'),
  ('CIAP2', 'T90', 'Diabetes não insulino-dependente', '200001', NULL, '{"chapter":"T","component":"7"}'),
  ('CIAP2', 'R96', 'Asma', '200001', NULL, '{"chapter":"R","component":"7"}'),
  ('CIAP2', 'P76', 'Perturbações depressivas', '200001', NULL, '{"chapter":"P","component":"7"}'),
  ('CIAP2', 'A97', 'Sem doença', '200001', NULL, '{"chapter":"A","component":"7"}'),

  ('CBO', '225125', 'Médico clínico', '200201', NULL, '{"family":"2251"}'),
  ('CBO', '225142', 'Médico da estratégia de saúde da família', '200201', NULL, '{"family":"2251"}'),
  ('CBO', '223505', 'Enfermeiro', '200201', NULL, '{"family":"2235"}'),
  ('CBO', '322205', 'Técnico de enfermagem', '200201', NULL, '{"family":"3222"}'),
  ('CBO', '515105', 'Agente comunitário de saúde', '200201', NULL, '{"family":"5151"}');
