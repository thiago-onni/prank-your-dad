{{/*
Helpers compartilhados pelos charts de componente do SUS Nexus.
Os cinco charts (core, fhir, web, ai, connector) usam EXATAMENTE os mesmos templates; só Chart.yaml e
values.yaml diferem. Altere aqui e replique com platform/helm/scripts/sync-chart-templates.sh.
*/}}

{{- define "sus.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sus.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "sus.namespace" -}}
{{- default .Release.Namespace .Values.namespaceOverride -}}
{{- end -}}

{{- define "sus.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sus.labels" -}}
helm.sh/chart: {{ include "sus.chart" . }}
{{ include "sus.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: sus-nexus
app.kubernetes.io/component: {{ .Values.component }}
sus-nexus.gov.br/tier: {{ .Values.tier }}
{{- with .Values.extraLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{- define "sus.selectorLabels" -}}
app.kubernetes.io/name: {{ include "sus.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "sus.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "sus.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "sus.image" -}}
{{- $registry := .Values.global.imageRegistry | default .Values.image.registry -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- if .Values.image.digest -}}
{{- printf "%s/%s@%s" $registry .Values.image.repository .Values.image.digest -}}
{{- else -}}
{{- printf "%s/%s:%s" $registry .Values.image.repository $tag -}}
{{- end -}}
{{- end -}}

{{/*
"true" quando o workload deve ser renderizado. Conectores com connector.edgeAgent=true rodam fora do
cluster (agente de borda): o chart não cria workload; o umbrella cria apenas KafkaUser/ACL.
*/}}
{{- define "sus.workloadEnabled" -}}
{{- if and .Values.connector .Values.connector.edgeAgent -}}false{{- else -}}true{{- end -}}
{{- end -}}

{{- define "sus.configMapName" -}}
{{- printf "%s-config" (include "sus.fullname" .) -}}
{{- end -}}

{{- define "sus.secretName" -}}
{{- .Values.externalSecret.targetName | default (printf "%s-secrets" (include "sus.fullname" .)) -}}
{{- end -}}

{{/* Annotation com hash da config para forçar rollout quando ConfigMap muda */}}
{{- define "sus.configChecksum" -}}
{{- include (print .Template.BasePath "/configmap.yaml") . | sha256sum -}}
{{- end -}}

{{/*
Pod template compartilhado entre Deployment e Rollout (Argo Rollouts).
*/}}
{{- define "sus.podTemplate" -}}
metadata:
  labels:
    {{- include "sus.selectorLabels" . | nindent 4 }}
    {{- with .Values.podLabels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  annotations:
    checksum/config: {{ include "sus.configChecksum" . }}
    kubectl.kubernetes.io/default-container: {{ .Chart.Name }}
    {{- with .Values.podAnnotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  {{- with (.Values.global.imagePullSecrets | default .Values.imagePullSecrets) }}
  imagePullSecrets:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  serviceAccountName: {{ include "sus.serviceAccountName" . }}
  automountServiceAccountToken: {{ .Values.serviceAccount.automount }}
  securityContext:
    {{- toYaml .Values.podSecurityContext | nindent 4 }}
  terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds }}
  {{- with .Values.priorityClassName }}
  priorityClassName: {{ . }}
  {{- end }}
  {{- with .Values.initContainers }}
  initContainers:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  containers:
    - name: {{ .Chart.Name }}
      image: {{ include "sus.image" . | quote }}
      imagePullPolicy: {{ .Values.image.pullPolicy }}
      securityContext:
        {{- toYaml .Values.securityContext | nindent 8 }}
      {{- with .Values.command }}
      command:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.args }}
      args:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      ports:
        - name: http
          containerPort: {{ .Values.containerPort }}
          protocol: TCP
        {{- range .Values.extraPorts }}
        - name: {{ .name }}
          containerPort: {{ .containerPort }}
          protocol: {{ .protocol | default "TCP" }}
        {{- end }}
      env:
        - name: POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: OTEL_SERVICE_NAME
          value: {{ include "sus.fullname" . }}
        - name: OTEL_RESOURCE_ATTRIBUTES
          value: "deployment.environment={{ .Values.global.environment }},service.namespace=sus-nexus,k8s.namespace.name=$(POD_NAMESPACE),k8s.pod.name=$(POD_NAME)"
        {{- if .Values.otel.enabled }}
        - name: OTEL_EXPORTER_OTLP_ENDPOINT
          value: {{ .Values.otel.endpoint | quote }}
        - name: OTEL_EXPORTER_OTLP_PROTOCOL
          value: {{ .Values.otel.protocol | quote }}
        {{- end }}
        {{- with .Values.env }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      envFrom:
        - configMapRef:
            name: {{ include "sus.configMapName" . }}
        {{- if .Values.externalSecret.enabled }}
        - secretRef:
            name: {{ include "sus.secretName" . }}
        {{- end }}
        {{- with .Values.envFrom }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- if .Values.probes.startup.enabled }}
      startupProbe:
        httpGet:
          path: {{ .Values.probes.startup.path }}
          port: http
        periodSeconds: {{ .Values.probes.startup.periodSeconds }}
        failureThreshold: {{ .Values.probes.startup.failureThreshold }}
        timeoutSeconds: {{ .Values.probes.startup.timeoutSeconds }}
      {{- end }}
      livenessProbe:
        httpGet:
          path: {{ .Values.probes.liveness.path }}
          port: http
        initialDelaySeconds: {{ .Values.probes.liveness.initialDelaySeconds }}
        periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
        failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
        timeoutSeconds: {{ .Values.probes.liveness.timeoutSeconds }}
      readinessProbe:
        httpGet:
          path: {{ .Values.probes.readiness.path }}
          port: http
        initialDelaySeconds: {{ .Values.probes.readiness.initialDelaySeconds }}
        periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
        failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
        timeoutSeconds: {{ .Values.probes.readiness.timeoutSeconds }}
      {{- with .Values.lifecycle }}
      lifecycle:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      resources:
        {{- toYaml .Values.resources | nindent 8 }}
      volumeMounts:
        - name: tmp
          mountPath: /tmp
        {{- range .Values.writableDirs }}
        - name: {{ .name }}
          mountPath: {{ .mountPath }}
        {{- end }}
        {{- with .Values.extraVolumeMounts }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    {{- with .Values.sidecars }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  volumes:
    - name: tmp
      emptyDir:
        sizeLimit: {{ .Values.tmpSizeLimit }}
    {{- range .Values.writableDirs }}
    - name: {{ .name }}
      emptyDir:
        sizeLimit: {{ .sizeLimit | default "256Mi" }}
    {{- end }}
    {{- with .Values.extraVolumes }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  {{- with .Values.nodeSelector }}
  nodeSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.tolerations }}
  tolerations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- if .Values.affinity }}
  affinity:
    {{- toYaml .Values.affinity | nindent 4 }}
  {{- else }}
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 100
          podAffinityTerm:
            topologyKey: kubernetes.io/hostname
            labelSelector:
              matchLabels:
                {{- include "sus.selectorLabels" . | nindent 16 }}
  {{- end }}
  {{- if .Values.topologySpreadConstraints }}
  topologySpreadConstraints:
    {{- toYaml .Values.topologySpreadConstraints | nindent 4 }}
  {{- else if .Values.global.multiZone }}
  topologySpreadConstraints:
    - maxSkew: 1
      topologyKey: topology.kubernetes.io/zone
      whenUnsatisfiable: ScheduleAnyway
      labelSelector:
        matchLabels:
          {{- include "sus.selectorLabels" . | nindent 10 }}
  {{- end }}
{{- end -}}
