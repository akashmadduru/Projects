{{/* Labels that identify a workload; also used as the pod selector. */}}
{{- define "ecommerce.selector" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: ecommerce-platform
{{- end -}}

{{- define "ecommerce.labels" -}}
{{ include "ecommerce.selector" . }}
app.kubernetes.io/managed-by: Helm
{{- end -}}

{{/* Full image reference for a service. */}}
{{- define "ecommerce.image" -}}
{{ .root.Values.global.registry }}/{{ .root.Values.global.repositoryPrefix }}/{{ .name }}:{{ required "global.imageTag must be set (deploy.sh does this)" .root.Values.global.imageTag }}
{{- end -}}

{{/* "Is this the lean size profile?" -> "true" or "" */}}
{{- define "ecommerce.lean" -}}
{{- if eq .Values.global.sizeProfile "lean" -}}true{{- end -}}
{{- end -}}
