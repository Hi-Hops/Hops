#!/usr/bin/env bash
set -Eeuo pipefail

# This script is invoked on the managed EC2 host after CI and image publication.
# Database migrations are intentionally handled separately before advancing the
# recorded schema tree hashes; a failed deployment never attempts a DB rollback.
if (( $# != 8 )); then
  echo 'Usage: deploy.sh COMMIT SPRING_TREE AI_TREE SPRING_IMAGE AI_IMAGE WEB_IMAGE PUBLIC_HOST DEPLOYMENT_ID' >&2
  exit 2
fi

commit=$1
spring_tree=$2
ai_tree=$3
spring_image=$4
ai_image=$5
web_image=$6
public_host=$7
deployment_id=$8
app_home=${HOPS_DEPLOY_HOME:-/home/ec2-user}
state_dir=${HOPS_DEPLOY_STATE_DIR:-/var/lib/hops-deploy}

for tree in "$commit" "$spring_tree" "$ai_tree"; do
  [[ "$tree" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit or migration tree.' >&2; exit 2; }
done
[[ "$public_host" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || { echo 'Invalid public hostname.' >&2; exit 2; }
[[ "$deployment_id" =~ ^[0-9]+-[0-9]+$ ]] || { echo 'Invalid deployment ID.' >&2; exit 2; }

registry=${spring_image%%/*}
[[ "$registry" =~ ^[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com$ ]] || {
  echo 'Invalid ECR registry.' >&2; exit 2;
}
region=${registry#*.dkr.ecr.}
region=${region%.amazonaws.com}
for pair in "hops-spring=$spring_image" "hops-ai=$ai_image" "hops-frontend=$web_image"; do
  name=${pair%%=*}
  image=${pair#*=}
  prefix="$registry/$name@sha256:"
  [[ "$image" == "$prefix"* && "${image#"$prefix"}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "Invalid immutable image for $name." >&2; exit 2;
  }
done

for path in "$state_dir/spring-migrations.tree" "$state_dir/ai-migrations.tree" \
  "$app_home/.config/hops/spring.env" "$app_home/.config/hops/ai.env" \
  "$app_home/rds-global-bundle.pem"; do
  [[ -f "$path" ]] || { echo "Required deployment file is missing: $path" >&2; exit 1; }
done
[[ "$(<"$state_dir/spring-migrations.tree")" == "$spring_tree" ]] || {
  echo 'Spring migrations changed: apply and verify them separately before deployment.' >&2; exit 1;
}
[[ "$(<"$state_dir/ai-migrations.tree")" == "$ai_tree" ]] || {
  echo 'AI migrations changed: apply and verify them separately before deployment.' >&2; exit 1;
}
grep -Fxq "HOPS_CORS_ALLOWEDORIGINS=https://$public_host" "$app_home/.config/hops/spring.env" || {
  echo 'Public origin does not match the configured Spring allowed origin.' >&2; exit 1;
}

exec 9>"$state_dir/deploy.lock"
flock -n 9 || { echo 'Another deployment is active.' >&2; exit 1; }

backup_prefix="hops-deploy-$deployment_id"
for name in hops-spring hops-ai hops-nginx hops-redis; do
  [[ "$(docker inspect --format '{{.State.Running}}' "$name")" == true ]] || {
    echo "Required container is not running: $name" >&2; exit 1;
  }
done
for name in hops-spring hops-ai hops-nginx; do
  if docker container inspect "$backup_prefix-$name" >/dev/null 2>&1; then
    echo "Deployment backup name already exists: $backup_prefix-$name" >&2; exit 1
  fi
done

docker_config=$(mktemp -d)
export DOCKER_CONFIG="$docker_config"
cleanup() { rm -rf -- "$docker_config"; }
trap cleanup EXIT
aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry" >/dev/null
docker pull "$spring_image"
docker pull "$ai_image"
docker pull "$web_image"
# Render and validate the configuration from the exact frontend image before stopping services.
docker run --rm --network hops-internal \
  -e "HOPS_PUBLIC_HOST=$public_host" -e 'NGINX_ENVSUBST_FILTER=^HOPS_PUBLIC_HOST$' \
  -v hops-letsencrypt:/etc/letsencrypt:ro \
  -v hops-certbot-webroot:/var/www/certbot:ro "$web_image" nginx -t

mutating=0
rollback() {
  local exit_status=$?
  trap - ERR
  set +e
  if (( mutating )); then
    echo 'Deployment failed; restoring the previous containers.' >&2
    local name
    for name in hops-spring hops-ai hops-nginx; do
      if docker container inspect "$backup_prefix-$name" >/dev/null 2>&1; then
        if docker container inspect "$name" >/dev/null 2>&1; then
          docker rm -f "$name" >/dev/null
        fi
        docker rename "$backup_prefix-$name" "$name"
      fi
      if docker container inspect "$name" >/dev/null 2>&1; then
        docker start "$name" >/dev/null
      fi
    done
    echo 'Rollback was attempted; verify all services and investigate the failed deployment.' >&2
  fi
  exit "$exit_status"
}
trap rollback ERR

mutating=1
for name in hops-nginx hops-spring hops-ai; do
  docker stop "$name" >/dev/null
  docker rename "$name" "$backup_prefix-$name"
done

docker run -d --name hops-spring --network hops-internal --restart unless-stopped \
  --env-file "$app_home/.config/hops/spring.env" \
  -v "$app_home/rds-global-bundle.pem:/etc/hops/rds-global-bundle.pem:ro" \
  -p 127.0.0.1:8080:8080 "$spring_image" >/dev/null
docker run -d --name hops-ai --network hops-internal --restart unless-stopped \
  --env-file "$app_home/.config/hops/ai.env" \
  -v "$app_home/rds-global-bundle.pem:/etc/hops/rds-global-bundle.pem:ro" \
  -p 127.0.0.1:8000:8000 "$ai_image" >/dev/null

wait_for_http() {
  local url=$1 expected=$2 status attempt
  for attempt in {1..40}; do
    status=$(curl -sS --noproxy '*' --max-time 3 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)
    [[ "$status" == "$expected" ]] && return 0
    sleep 3
  done
  echo "Health check failed: $url (expected $expected, got $status)" >&2
  return 1
}
wait_for_http http://127.0.0.1:8080/api/v2/auth/me 401
wait_for_http http://127.0.0.1:8000/healthz 200

docker run -d --name hops-nginx --network hops-internal --restart unless-stopped \
  -p 80:80 -p 443:443 \
  -e "HOPS_PUBLIC_HOST=$public_host" -e 'NGINX_ENVSUBST_FILTER=^HOPS_PUBLIC_HOST$' \
  -v hops-letsencrypt:/etc/letsencrypt:ro \
  -v hops-certbot-webroot:/var/www/certbot:ro "$web_image" >/dev/null
docker exec hops-nginx nginx -t
status=$(curl -sS --noproxy '*' --max-time 10 --resolve "$public_host:443:127.0.0.1" \
  -o /dev/null -w '%{http_code}' "https://$public_host/")
[[ "$status" == 200 ]] || { echo "HTTPS frontend check failed: $status" >&2; false; }
status=$(curl -sS --noproxy '*' --max-time 10 --resolve "$public_host:443:127.0.0.1" \
  -o /dev/null -w '%{http_code}' "https://$public_host/api/v2/auth/me")
[[ "$status" == 401 ]] || { echo "HTTPS API check failed: $status" >&2; false; }
status=$(curl -sS --noproxy '*' --max-time 10 --resolve "$public_host:443:127.0.0.1" \
  -o /dev/null -w '%{http_code} %{content_type}' "https://$public_host/v3/api-docs/swagger-config")
[[ "$status" == '200 application/json'* ]] || { echo "HTTPS OpenAPI check failed: $status" >&2; false; }
status=$(curl -sS --noproxy '*' --max-time 10 --resolve "$public_host:443:127.0.0.1" \
  -o /dev/null -w '%{http_code} %{content_type}' "https://$public_host/swagger-ui/swagger-ui-bundle.js")
[[ "$status" == '200 text/javascript'* || "$status" == '200 application/javascript'* ]] || {
  echo "HTTPS Swagger asset check failed: $status" >&2; false;
}

# A successful deployment retains the previous images locally, but the stopped
# containers are no longer needed. Migrations and persisted volumes are untouched.
printf '%s\n' "$commit" > "$state_dir/deployed-commit"
mutating=0
trap - ERR
for name in hops-spring hops-ai hops-nginx; do
  docker rm "$backup_prefix-$name" >/dev/null || echo "Could not remove old container: $name" >&2
done
echo "Deployment succeeded for commit $commit."
