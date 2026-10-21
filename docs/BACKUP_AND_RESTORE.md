# Backup and restore

The whole system's data (orders, payments, menus, photos, accounts) is in one MySQL database, in the Docker volume `mysql-data`. Nothing else needs backing up except your `.env` file (keep it somewhere safe and private: it holds the passwords and the JWT secret).

## Back up

```bash
./ops/backup-mysql.sh            # writes ./backups/kashmir_menu-<date>.sql.gz
```

Safe while the restaurant is open. The script refuses to leave behind an empty or truncated file. Then copy the file off this machine. Suggested rhythm: nightly, and keep at least 7 daily and 4 weekly copies. To schedule it on Windows, use Task Scheduler to run the command above from the project folder; on Linux, a cron line such as `15 3 * * * cd /path/to/kashmirmenu && ./ops/backup-mysql.sh`.

## Restore (replaces the current data: read first)

Restoring overwrites what is in the database. Do it into a **throwaway copy first** to prove the backup works, and only restore over live data when you mean to.

Practice restore into a scratch database (does not touch live data):

```bash
docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS restore_check"'
gzip -dc backups/kashmir_menu-YYYYmmdd-HHMMSS.sql.gz | docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" restore_check'
docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT COUNT(*) FROM restore_check.orders"'
docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE restore_check"'
```

Real restore (after stopping the app so nothing writes during it): `docker compose stop app frontend`, load the dump into `$MYSQL_DATABASE` the same way, then `docker compose start app frontend`. The application's migrations are recorded inside the database, so a restored dump starts cleanly on the same or a newer version of the app.

## What is not covered

Point-in-time recovery (restoring to a minute between two backups) needs MySQL binary logs, which are not configured. A backup shows you the state at the time it was taken; orders placed after it would be lost in a restore.
