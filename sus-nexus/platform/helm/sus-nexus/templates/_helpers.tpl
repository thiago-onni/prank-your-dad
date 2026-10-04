{{- define "umbrella.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: sus-nexus
app.kubernetes.io/instance: {{ .Release.Name }}
sus-nexus.gov.br/environment: {{ .Values.global.environment }}
{{- end -}}

{{- define "umbrella.ns.data" -}}{{ .Values.global.namespaces.data }}{{- end -}}
{{- define "umbrella.ns.security" -}}{{ .Values.global.namespaces.security }}{{- end -}}
{{- define "umbrella.ns.workflows" -}}{{ .Values.global.namespaces.workflows }}{{- end -}}

{{/* Nome do secret de credenciais do object store de backup (criado via ExternalSecret) */}}
{{- define "umbrella.backupCredsSecret" -}}sus-backup-s3-credentials{{- end -}}
