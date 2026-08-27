#!/usr/bin/env bash
set -euo pipefail

minecraft_version="${1:?Minecraft version is required}"
loader_version="${2:-0.19.3}"
installer_version="${3:-1.1.2}"
mod_jar="${4:?Compat Login JAR is required, for example build/libs/compat_login-1.21.11-1.1.0.jar}"

if [[ ! -f "${mod_jar}" ]]; then
  echo "Compat Login JAR not found: ${mod_jar}" >&2
  exit 2
fi

work_dir="$(mktemp -d)"
cleanup() {
  if [[ -n "${server_pid:-}" ]] && kill -0 "${server_pid}" 2>/dev/null; then
    kill "${server_pid}" 2>/dev/null || true
  fi
  rm -rf "${work_dir}"
}
trap cleanup EXIT

mkdir -p "${work_dir}/mods"
cp "${mod_jar}" "${work_dir}/mods/"
curl --fail --location --silent --show-error \
  "https://meta.fabricmc.net/v2/versions/loader/${minecraft_version}/${loader_version}/${installer_version}/server/jar" \
  --output "${work_dir}/fabric-server.jar"

printf 'eula=true\n' > "${work_dir}/eula.txt"
printf '%s\n' \
  'online-mode=true' \
  'enforce-secure-profile=false' \
  'server-port=25565' \
  'view-distance=2' \
  'simulation-distance=2' \
  'sync-chunk-writes=false' \
  > "${work_dir}/server.properties"

mkfifo "${work_dir}/server-input"
exec 3<>"${work_dir}/server-input"

(
  cd "${work_dir}"
  java -Xms256M -Xmx1G -jar fabric-server.jar nogui \
    < server-input > server.log 2>&1
) &
server_pid=$!

started=false
# The window also covers downloading the vanilla server JAR and generating the first world.
for _ in $(seq 1 "${timeout_seconds:-300}"); do
  if grep -Fq 'Done (' "${work_dir}/server.log" 2>/dev/null; then
    started=true
    break
  fi
  if ! kill -0 "${server_pid}" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [[ "${started}" == true ]]; then
  printf 'stop\n' >&3
else
  kill "${server_pid}" 2>/dev/null || true
fi

set +e
wait "${server_pid}"
server_exit=$?
set -e
server_pid=''

cat "${work_dir}/server.log"

grep -Fq "Loading Minecraft ${minecraft_version} with Fabric Loader ${loader_version}" "${work_dir}/server.log"
grep -Fq 'Compat Login initialized with 2 enabled authentication service(s)' "${work_dir}/server.log"
grep -Fq 'Done (' "${work_dir}/server.log"

if grep -Fq 'Compat Login requires online-mode=true' "${work_dir}/server.log"; then
  echo 'online-mode=true was incorrectly rejected' >&2
  exit 1
fi

if grep -Eq 'Mixin apply failed|InjectionError|InvalidInjectionException' "${work_dir}/server.log"; then
  echo 'Compat Login mixin failed to apply' >&2
  exit 1
fi

if [[ ${server_exit} -ne 0 ]]; then
  echo "Minecraft server exited with code ${server_exit}" >&2
  exit "${server_exit}"
fi

echo "Smoke test passed for Minecraft ${minecraft_version}, Fabric Loader ${loader_version}, $(basename "${mod_jar}")"
