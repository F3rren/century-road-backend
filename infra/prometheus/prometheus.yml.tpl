# A template, not a config: @AUTH_PORT@ and @GATEWAY_PORT@ are filled in from the environment
# when the container starts (see the prometheus entrypoint in compose-dev.yml and compose-prod.yml), because
# Prometheus cannot read environment variables itself. Not valid as it stands.
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: century-road-auth-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["auth-service:@AUTH_PORT@"]

  - job_name: century-road-history-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["history-service:8082"]

  - job_name: century-road-gateway
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["gateway:@GATEWAY_PORT@"]
