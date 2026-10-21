#!/usr/bin/env bash
# Consistent logical backup of the Kashmir Menu database, from the running Docker stack.
#
#   ./ops/backup-mysql.sh [output-directory]        (default: ./backups)
#
# Writes kashmir_menu-YYYYmmdd-HHMMSS.sql.gz. --single-transaction takes a consistent snapshot without
# locking the tables, so it is safe while the restaurant is trading. The password never leaves the container
# (it is read from the container's own environment), so nothing secret is on this command line or in this file.
#
# A backup on the same disk as the database is not a backup against disk loss: copy the file somewhere else
# (another machine, cloud storage) after it is made. Test a restore from time to time: see docs/BACKUP_AND_RESTORE.md.
set -euo pipefail

OUT_DIR="${1:-./backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="${OUT_DIR}/kashmir_menu-${STAMP}.sql.gz"
mkdir -p "${OUT_DIR}"

docker compose exec -T mysql sh -c \
  'mysqldump --single-transaction --routines --triggers --set-gtid-purged=OFF -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" 2>/dev/null' \
  | gzip > "${FILE}"

# A dump that is only the gzip header, or lacks the end marker mysqldump writes, is not a backup.
if [ "$(gzip -dc "${FILE}" | wc -c)" -lt 1000 ] || ! gzip -dc "${FILE}" | tail -n 5 | grep -q "Dump completed"; then
  rm -f "${FILE}"
  echo "Backup failed: the dump was empty or incomplete." >&2
  exit 1
fi
echo "Backup written: ${FILE} ($(du -h "${FILE}" | cut -f1))"
