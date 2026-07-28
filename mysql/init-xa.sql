-- =====================================================================
-- MySQL 8.4.x 側で 2PC(XA) を動かすための初期設定。
--
--   mysql -h <host> -u root -p < mysql/init-xa.sql
--
-- パスワードはプレースホルダ。本番では Secrets Manager 等で払い出すこと。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) スキーマ（XA は InnoDB のみサポート。既定エンジンが InnoDB であること）
--    テーブル自体はアプリ起動時に SchemaInitializer が CREATE TABLE IF NOT EXISTS する。
-- ---------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS DHCOMAP DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS DHINFAP DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 2) 接続ユーザー
--    MySQL 8.4 では mysql_native_password プラグインが既定で無効化されている。
--    既定の caching_sha2_password を使う（Connector/J 9.7.0 は対応済み）。
--    非 TLS 接続で使う場合のみ JDBC URL に allowPublicKeyRetrieval=true が必要。
-- ---------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'dhcomap_user'@'%' IDENTIFIED WITH caching_sha2_password BY '__CHANGE_ME__';
CREATE USER IF NOT EXISTS 'dhinfap_user'@'%' IDENTIFIED WITH caching_sha2_password BY '__CHANGE_ME__';

GRANT ALL PRIVILEGES ON DHCOMAP.* TO 'dhcomap_user'@'%';
GRANT ALL PRIVILEGES ON DHINFAP.* TO 'dhinfap_user'@'%';

-- ---------------------------------------------------------------------
-- 3) 【必須】XA リカバリ用の権限
--    MySQL 8.0 以降、XA RECOVER は XA_RECOVER_ADMIN 権限を持つユーザーのみ実行できる。
--    WildFly の periodic recovery（XA データソースの recovery-username で接続する）が
--    XA RECOVER を発行するため、これが無いとサーバログに
--      "Access denied; you need (at least one of) the XA_RECOVER_ADMIN privilege(s)"
--    が出続け、in-doubt ブランチを回収できなくなる。
--    XA_RECOVER_ADMIN は動的権限のため *.* に対して付与する。
-- ---------------------------------------------------------------------
GRANT XA_RECOVER_ADMIN ON *.* TO 'dhcomap_user'@'%';
GRANT XA_RECOVER_ADMIN ON *.* TO 'dhinfap_user'@'%';

FLUSH PRIVILEGES;

-- ---------------------------------------------------------------------
-- 4) 確認用
--    xa_detach_on_prepare は 8.0.29 以降 ON が既定。ON のままにしておくこと
--    （XA PREPARE 後にブランチがセッションから切り離され、コネクションプールと
--      リカバリの双方にとって都合が良い）。
-- ---------------------------------------------------------------------
-- SHOW VARIABLES LIKE 'xa_detach_on_prepare';
-- SHOW ENGINES;                  -- InnoDB が DEFAULT であること
-- XA RECOVER;                    -- in-doubt ブランチが残っていないこと
