#!/usr/bin/env bash
# Genera los secretos de desarrollo local. Nunca se usan fuera de esta máquina y nunca entran en Git.
#
#   .env                         copia de .env.example con una contraseña aleatoria de PostgreSQL
#   secrets/jwt-dev-private.pem  RSA de 2048 bits para firmar los access tokens (OW-013)
#   secrets/jwt-dev-public.pem   su clave pública
#   secrets/encryption-dev-key   AES-256 en Base64 para los headers cifrados de los monitores (OW-022)
#
# No sobrescribe nada: para regenerar un secreto, borrar su fichero y volver a ejecutar.
# En Windows se ejecuta desde Git Bash, que incluye openssl.
set -euo pipefail

cd "$(dirname "$0")/.."
umask 077

if ! command -v openssl > /dev/null; then
  echo "Error: hace falta openssl" >&2
  exit 1
fi

if [[ -f .env ]]; then
  echo "Se conserva .env"
else
  # Hexadecimal: sin caracteres que haya que escapar en .env ni en una URL
  password=$(openssl rand -hex 24)
  sed "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=${password}/" .env.example > .env
  echo "Creado .env con una contraseña aleatoria de PostgreSQL"
fi

mkdir -p secrets

if [[ -f secrets/jwt-dev-private.pem ]]; then
  echo "Se conserva secrets/jwt-dev-private.pem"
else
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-dev-private.pem 2> /dev/null
  rm -f secrets/jwt-dev-public.pem
  echo "Creada secrets/jwt-dev-private.pem"
fi

if [[ -f secrets/jwt-dev-public.pem ]]; then
  echo "Se conserva secrets/jwt-dev-public.pem"
else
  openssl pkey -in secrets/jwt-dev-private.pem -pubout -out secrets/jwt-dev-public.pem
  echo "Creada secrets/jwt-dev-public.pem"
fi

if [[ -f secrets/encryption-dev-key ]]; then
  echo "Se conserva secrets/encryption-dev-key"
else
  openssl rand -base64 32 > secrets/encryption-dev-key
  echo "Creada secrets/encryption-dev-key"
fi
