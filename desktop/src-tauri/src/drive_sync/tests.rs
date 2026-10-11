use super::*;
use flate2::{write::GzEncoder, Compression};
use reqwest::StatusCode;
use rusqlite::{params, Connection};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Write;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use uuid::Uuid;

#[test]
fn google_reconnect_policy_does_not_confuse_network_failures_with_revocation() {
    assert!(google_reconnect_required("Google Drive request failed (HTTP 401 Unauthorized)"));
    assert!(google_reconnect_required("Google authorization failed (invalid_grant)"));
    assert!(google_reconnect_required("Google Drive needs you to reconnect"));
    assert!(!google_reconnect_required("Google Drive request failed (HTTP 503 Service Unavailable)"));
    assert!(!google_reconnect_required("Google Drive request failed (HTTP 403 Forbidden)"));
}

#[test]
    fn unordered_drive_pages_are_sorted_before_cursor_checkpointing() {
    let make = |id: &str, created: &str| DriveFileRecord {
        id: id.to_string(), name: "history.json.gz".into(), size: None,
        created_time: Some(created.to_string()), modified_time: None,
        app_properties: HashMap::new(),
    };
    let mut files = vec![
        make("late", "2026-01-03T00:00:00Z"),
        make("tie-z", "2026-01-02T00:00:00Z"),
        make("early", "2026-01-01T00:00:00Z"),
        make("tie-a", "2026-01-02T00:00:00Z"),
    ];
    sort_batches_oldest_first(&mut files);
    assert_eq!(
        files.iter().map(|file| file.id.as_str()).collect::<Vec<_>>(),
        vec!["early", "tie-a", "tie-z", "late"]
    );
}

fn history_storage_fixture() -> (std::path::PathBuf, Connection) {
    let directory = std::env::temp_dir().join(format!("tempo-drive-history-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&directory).unwrap();
    drop(crate::db::Database::new(&db_path(&directory)).unwrap());
    let conn = open_sync_db(&directory).unwrap();
    (directory, conn)
}


#[test]
fn cross_account_imports_do_not_merge_same_title_and_timestamp() {
    let (directory, conn) = history_storage_fixture();
    let first = fixture_batch().events[0].clone();
    assert!(insert_remote_event_for_account(
        &conn, "remote-a", &first, "google-account-a").unwrap());
    let mut second = first;
    second.event_id = "b".repeat(64);
    second.timestamp_utc += 500;
    assert!(insert_remote_event_for_account(
        &conn, "remote-b", &second, "google-account-b").unwrap(),
        "an imported play from account A cannot consume B's distinct event");
    let owners: Vec<String> = conn.prepare(
        "SELECT owner_account_subject FROM drive_event_state ORDER BY scrobble_id"
    ).unwrap().query_map([], |row| row.get(0)).unwrap()
        .collect::<Result<_, _>>().unwrap();
    assert_eq!(owners, vec!["google-account-a", "google-account-b"]);
    assert!(!insert_remote_event_for_account(
        &conn, "remote-b", &second, "google-account-b").unwrap());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn unknown_legacy_import_owner_is_quarantined_and_never_matches_new_account() {
    let (directory, conn) = history_storage_fixture();
    let first = fixture_batch().events[0].clone();
    assert!(insert_remote_event_for_account(
        &conn, "remote-a", &first, "google-account-a").unwrap());
    conn.execute("UPDATE drive_event_state SET owner_account_subject = NULL
         WHERE drive_imported = 1", []).unwrap();
    conn.execute("DELETE FROM drive_sync_migrations
         WHERE name = 'legacy_aliases_and_account_v1'", []).unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let quarantined: String = conn.query_row(
        "SELECT owner_account_subject FROM drive_event_state WHERE drive_imported = 1",
        [], |row| row.get(0)
    ).unwrap();
    assert_eq!(quarantined, LEGACY_UNVERIFIED_ACCOUNT);
    let mut second = first;
    second.event_id = "c".repeat(64);
    assert!(insert_remote_event_for_account(
        &conn, "remote-b", &second, "google-account-b").unwrap());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn the_same_producer_id_can_be_imported_into_two_google_accounts() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    assert!(insert_remote_event_for_account(
        &conn, "source-device", &original, "subject-a").unwrap());
    assert!(!insert_remote_event_for_account(
        &conn, "source-device", &original, "subject-a").unwrap());
    assert!(insert_remote_event_for_account(
        &conn, "source-device", &original, "subject-b").unwrap());
    let state_count: i64 = conn.query_row(
        "SELECT count(*) FROM drive_event_state
         WHERE origin_event_id = ?1",
        [&original.event_id], |row| row.get(0),
    ).unwrap();
    let alias_count: i64 = conn.query_row(
        "SELECT count(*) FROM drive_event_aliases
         WHERE origin_event_id = ?1",
        [&original.event_id], |row| row.get(0),
    ).unwrap();
    assert_eq!((state_count, alias_count), (2, 2));
    assert_eq!(conn.query_row(
        "SELECT count(DISTINCT account_subject) FROM drive_event_aliases
         WHERE origin_event_id = ?1",
        [&original.event_id], |row| row.get::<_, i64>(0),
    ).unwrap(), 2);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn scrobble_deletion_prunes_origin_rows_without_a_full_reopen_scan() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
    let id: i64 = conn.query_row("SELECT id FROM scrobbles", [],
        |row| row.get(0)).unwrap();
    conn.execute("DELETE FROM scrobbles WHERE id = ?1", [id]).unwrap();
    let (states, aliases): (i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM drive_event_state),
                (SELECT count(*) FROM drive_event_aliases)",
        [], |row| Ok((row.get(0)?, row.get(1)?))
    ).unwrap();
    assert_eq!((states, aliases), (0, 0));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn alias_backfill_runs_once_and_keeps_legacy_origin_mapping() {
    let (directory, conn) = history_storage_fixture();
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('Track', 'Artist', 1700000000000)", []).unwrap();
    let id = conn.last_insert_rowid();
    let origin = "d".repeat(64);
    conn.execute("INSERT INTO drive_event_state
        (scrobble_id, origin_event_id, origin_device_id, drive_imported)
        VALUES (?1, ?2, 'old-desktop', 0)", params![id, origin]).unwrap();
    conn.execute("DELETE FROM drive_sync_migrations
        WHERE name = 'legacy_aliases_and_account_v1'", []).unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let mapped: i64 = conn.query_row(
        "SELECT scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&origin], |row| row.get(0)
    ).unwrap();
    assert_eq!(mapped, id);
    conn.execute("DELETE FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&origin]).unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let count: i64 = conn.query_row(
        "SELECT count(*) FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&origin], |row| row.get(0)
    ).unwrap();
    assert_eq!(count, 0, "backfill must not rescan all history on each open");
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn temporal_dedup_normalizes_accents_and_whitespace_and_skips_other_candidates() {
    let (directory, conn) = history_storage_fixture();
    let mut event = fixture_batch().events[0].clone();
    event.title = "été 🔊".into();
    event.artist = "björk".into();
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc) VALUES ('Other', 'Artist', ?1)",
        [event.timestamp_utc]).unwrap();
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc) VALUES ('  ÉTÉ 🔊  ', ' BJÖRK ', ?1)",
        [event.timestamp_utc + 500]).unwrap();
    let matching_id = conn.last_insert_rowid();
    assert!(!insert_remote_event(&conn, "remote-device", &event).unwrap());
    let (count, recorded): (i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM scrobbles), scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&event.event_id], |row| Ok((row.get(0)?, row.get(1)?))
    ).unwrap();
    assert_eq!((count, recorded), (2, matching_id));
    assert!(!insert_remote_event(&conn, "remote-device", &event).unwrap());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn remote_copy_does_not_claim_a_legacy_unpinned_local_capture() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES (?1, ?2, ?3)",
        params![event.title, event.artist, event.timestamp_utc],
    ).unwrap();
    let local_id = conn.last_insert_rowid();
    assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
    let (imported, own_id, own_device): (i64, String, String) = conn.query_row(
        "SELECT drive_imported, origin_event_id, origin_device_id
         FROM drive_event_state WHERE scrobble_id = ?1",
        [local_id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    ).unwrap();
    assert_eq!(imported, 0);
    assert_eq!(own_device, load_state(&conn).unwrap().device_id);
    assert_ne!(own_id, event.event_id);
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn remote_temporal_match_never_converts_local_capture_into_imported_play() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES (?1, ?2, ?3)",
        params![event.title, event.artist, event.timestamp_utc],
    ).unwrap();
    let local_id = conn.last_insert_rowid();
    let device_id = load_state(&conn).unwrap().device_id;
    let own_origin = pin_local_origin(
        &conn, &device_id, local_id, event.timestamp_utc,
        &event.title, &event.artist,
    ).unwrap();

    assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
    let (original_id, is_imported, sent_at): (String, i64, Option<i64>) =
        conn.query_row(
            "SELECT origin_event_id, drive_imported, drive_uploaded_at
             FROM drive_event_state WHERE scrobble_id = ?1",
            [local_id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        ).unwrap();
    assert_eq!(original_id, own_origin);
    assert_eq!(is_imported, 0, "a local capture cannot become a cloud import");
    assert_eq!(sent_at, None);
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1,
        "the Desktop producer must still be able to publish its own event");
    let alias_id: i64 = conn.query_row(
        "SELECT scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&event.event_id], |row| row.get(0),
    ).unwrap();
    assert_eq!(alias_id, local_id);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}


#[test]
fn temporally_close_different_versions_are_not_collapsed() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    let mut remix = original.clone();
    remix.event_id = "c".repeat(64);
    remix.timestamp_utc += 500;
    remix.duration_ms += 30_000;
    assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
    assert!(insert_remote_event(&conn, "device-two", &remix).unwrap(),
        "recordings with substantially different known lengths are not the same play");
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles", [],
        |row| row.get(0)).unwrap();
    assert_eq!(count, 2);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn independent_cross_device_replay_seven_seconds_later_stays_distinct() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    let mut replay = original.clone();
    replay.event_id = "a".repeat(64);
    replay.timestamp_utc += 7_000;
    assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
    assert!(insert_remote_event(&conn, "device-two", &replay).unwrap(),
        "a 7-second independent replay must not be lost to temporal deduplication");
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles", [],
        |row| row.get(0)).unwrap();
    assert_eq!(count, 2);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn separate_capture_app_session_ids_do_not_hide_a_single_play() {
    let (directory, conn) = history_storage_fixture();
    let mut first = fixture_batch().events[0].clone();
    first.session_id = Some("session-one".into());
    let mut second = first.clone();
    second.session_id = Some("session-two".into());
    second.event_id = "a".repeat(64);
    second.timestamp_utc += 500;
    assert!(insert_remote_event(&conn, "device-one", &first).unwrap());
    assert!(!insert_remote_event(&conn, "device-two", &second).unwrap(),
        "source-local session IDs are not proof of distinct physical plays");
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles", [],
        |row| row.get(0)).unwrap();
    assert_eq!(count, 1);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn different_devices_playing_same_short_track_25_seconds_apart_stay_distinct() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    let mut second_play = original.clone();
    second_play.event_id = "a".repeat(64);
    second_play.timestamp_utc += 25_000;
    assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
    assert!(insert_remote_event(&conn, "device-two", &second_play).unwrap(),
        "a second 25-second replay on another device cannot be deduplicated");
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles", [],
        |row| row.get(0)).unwrap();
    assert_eq!(count, 2);
    assert!(!insert_remote_event(&conn, "device-two", &second_play).unwrap(),
        "the second play must still be idempotent by its exact event ID");
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn every_reconciled_origin_survives_restart_without_collapsing_rapid_replays() {
    let (directory, conn) = history_storage_fixture();
    let first = fixture_batch().events[0].clone();
    let mut second_capture = first.clone();
    second_capture.event_id = "b".repeat(64);
    second_capture.timestamp_utc += 50;
    let mut third_capture = first.clone();
    third_capture.event_id = "c".repeat(64);
    third_capture.timestamp_utc += 100;
    assert!(insert_remote_event(&conn, "device-a", &first).unwrap());
    assert!(!insert_remote_event(&conn, "device-b", &second_capture).unwrap());
    assert!(!insert_remote_event(&conn, "device-c", &third_capture).unwrap());
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let mut replay = first.clone();
    replay.event_id = "d".repeat(64);
    replay.timestamp_utc += 1000;
    let mut replay_b = replay.clone();
    replay_b.event_id = "e".repeat(64);
    replay_b.timestamp_utc += 50;
    let mut replay_c = replay.clone();
    replay_c.event_id = "f".repeat(64);
    replay_c.timestamp_utc += 100;
    assert!(insert_remote_event(&conn, "device-a", &replay).unwrap());
    assert!(!insert_remote_event(&conn, "device-b", &replay_b).unwrap());
    assert!(!insert_remote_event(&conn, "device-c", &replay_c).unwrap());
    for (device, event) in [("device-a", &first), ("device-b", &second_capture),
        ("device-c", &third_capture), ("device-a", &replay), ("device-b", &replay_b), ("device-c", &replay_c)]
    {
        assert!(!insert_remote_event(&conn, device, event).unwrap());
    }
    let counts: (i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM scrobbles), (SELECT count(*) FROM drive_event_aliases)",
        [], |row| Ok((row.get(0)?, row.get(1)?))
    ).unwrap();
    assert_eq!(counts, (2, 6));
    let primary_origins: Vec<String> = conn.prepare("SELECT origin_event_id FROM drive_event_state ORDER BY scrobble_id")
        .unwrap().query_map([], |row| row.get(0)).unwrap().collect::<Result<_, _>>().unwrap();
    assert_eq!(primary_origins, vec![first.event_id, replay.event_id]);
    assert!(pending_local_plays(&conn).unwrap().is_empty());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn legacy_origins_migrate_with_one_rescan_and_deleted_rows_release_their_aliases() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
    conn.execute_batch("DROP TABLE drive_event_aliases;
        DELETE FROM drive_sync_migrations WHERE name IN
            ('reconciled_origins_v1', 'legacy_aliases_and_account_v1');
        UPDATE drive_sync_state SET download_cursor = 123 WHERE id = 1;").unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    assert_eq!(load_state(&conn).unwrap().download_cursor, 0);
    let source: String = conn.query_row("SELECT source_device_id FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&event.event_id], |row| row.get(0)).unwrap();
    assert_eq!(source, "device-a");
    conn.execute("UPDATE drive_sync_state SET download_cursor = 999 WHERE id = 1", []).unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    assert_eq!(load_state(&conn).unwrap().download_cursor, 999);
    conn.execute("DELETE FROM scrobbles", []).unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let counts: (i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM drive_event_state), (SELECT count(*) FROM drive_event_aliases)",
        [], |row| Ok((row.get(0)?, row.get(1)?))
    ).unwrap();
    assert_eq!(counts, (0, 0));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn origin_alias_failure_rolls_back_the_whole_import_transaction() {
    let (directory, conn) = history_storage_fixture();
    conn.execute_batch("CREATE TRIGGER fail_origin BEFORE INSERT ON drive_event_aliases
        BEGIN SELECT RAISE(ABORT, 'storage failure'); END;").unwrap();
    let transaction = conn.unchecked_transaction().unwrap();
    assert!(insert_remote_event(&transaction, "device-a", &fixture_batch().events[0]).is_err());
    drop(transaction);
    let counts: (i64, i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM scrobbles), (SELECT count(*) FROM drive_event_state), (SELECT count(*) FROM drive_event_aliases)",
        [], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?))
    ).unwrap();
    assert_eq!(counts, (0, 0, 0));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn rerecording_an_uploaded_origin_is_idempotent_and_cannot_move_its_identity() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
    let id: i64 = conn.query_row("SELECT id FROM scrobbles", [], |row| row.get(0)).unwrap();
    remember_origin(&conn, id, "device-a", &event.event_id).unwrap();
    remember_origin(&conn, id, "device-a", &event.event_id).unwrap();
    assert!(remember_origin(&conn, id + 1, "device-a", &event.event_id).is_err());
    assert!(remember_origin(&conn, id, "device-b", &event.event_id).is_err());
    let recorded: (i64, String) = conn.query_row("SELECT scrobble_id, source_device_id FROM drive_event_aliases",
        [], |row| Ok((row.get(0)?, row.get(1)?))).unwrap();
    assert_eq!(recorded, (id, "device-a".into()));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

fn oauth_storage_fixture() -> (std::path::PathBuf, Connection) {
    let directory = std::env::temp_dir().join(format!("tempo-oauth-test-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&directory).unwrap();
    let conn = Connection::open(db_path(&directory)).unwrap();
    conn.execute_batch("CREATE TABLE scrobbles (id INTEGER PRIMARY KEY); INSERT INTO scrobbles VALUES (1), (2);").unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    conn.execute_batch(
        "UPDATE drive_sync_state SET enabled = 1, account_email = 'old@example.com', account_subject = 'google-stable-123',
         access_token = 'old-access', refresh_token = 'old-refresh', token_expires_at = 9000000000000,
         download_cursor = 123, accepted_disable_version = 100 WHERE id = 1;
         INSERT INTO drive_event_state (scrobble_id, drive_imported, drive_uploaded_at) VALUES (1, 0, 200), (2, 1, 200);"
    ).unwrap();
    (directory, conn)
}


#[test]
fn keyring_credentials_are_never_reused_across_google_subjects() {
    let account_a = serde_json::to_string(&SubjectBoundRefreshToken {
        sub: "google-a".into(), refresh_token: "secret-a".into(),
    }).unwrap();
    let account_b = serde_json::to_string(&SubjectBoundRefreshToken {
        sub: "google-b".into(), refresh_token: "secret-b".into(),
    }).unwrap();
    assert_eq!(decode_subject_bound_token(&account_a, "google-a").unwrap(), "secret-a");
    assert_eq!(decode_subject_bound_token(&account_b, "google-b").unwrap(), "secret-b");
    assert!(decode_subject_bound_token(&account_b, "google-a").is_err(),
        "an interrupted B sign-in must not later refresh account A with B's keyring token");
    assert!(decode_subject_bound_token("old-plaintext-refresh-token", "google-a").is_err(),
        "unbound legacy credentials require fresh consent");
}

#[tokio::test]
async fn interrupted_credential_replacement_cannot_refresh_or_use_an_unknown_account() {
    let (directory, conn) = oauth_storage_fixture();
    prepare_oauth_credentials(&conn, true).unwrap();
    let state = load_state(&conn).unwrap();
    assert!(!state.enabled);
    assert!(state.account_email.is_none() && state.account_subject.is_none() && state.access_token.is_none() && state.refresh_token.is_none());
    assert_eq!((state.download_cursor, state.accepted_disable_version), (0, 0));
    drop(conn);
    assert!(access_token(&directory).await.unwrap_err().contains("identity is unavailable"));
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn reconnecting_the_same_account_stops_before_replacement_and_preserves_cursors() {
    let (directory, conn) = oauth_storage_fixture();
    prepare_oauth_credentials(&conn, false).unwrap();
    let state = load_state(&conn).unwrap();
    assert!(!state.enabled && state.account_email.is_none() && state.account_subject.is_none());
    assert_eq!((state.download_cursor, state.accepted_disable_version), (123, 100));
    let uploaded: i64 = conn.query_row("SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1", [], |row| row.get(0)).unwrap();
    assert_eq!(uploaded, 200);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn failure_to_prepare_credentials_rolls_back_cursors_before_any_keyring_write() {
    let (directory, conn) = oauth_storage_fixture();
    conn.execute_batch("CREATE TRIGGER fail_oauth_stop BEFORE UPDATE OF enabled ON drive_sync_state BEGIN SELECT RAISE(ABORT, 'storage failure'); END;").unwrap();
    assert!(prepare_oauth_credentials(&conn, true).is_err());
    let state = load_state(&conn).unwrap();
    assert!(state.enabled);
    assert_eq!(state.account_email.as_deref(), Some("old@example.com"));
    assert_eq!((state.download_cursor, state.accepted_disable_version), (123, 100));
    let uploaded: i64 = conn.query_row("SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1", [], |row| row.get(0)).unwrap();
    assert_eq!(uploaded, 200);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}


#[test]
fn switching_google_accounts_does_not_republish_previous_local_history() {
    let (directory, conn) = history_storage_fixture();
    conn.execute("UPDATE drive_sync_state SET account_subject = 'account-a',
        account_email = 'a@example.com', enabled = 1 WHERE id = 1", []).unwrap();
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('Old', 'Artist', 1700000000000)", []).unwrap();
    let old_id = conn.last_insert_rowid();
    let old_device = load_state(&conn).unwrap().device_id;
    pin_local_origin(&conn, &old_device, old_id, 1_700_000_000_000,
        "Old", "Artist").unwrap();
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    // The real settings UI disconnects before selecting another account.
    // Keep only the last verified subject (no usable token) until the next
    // sign-in so previously owned tracks remain account-isolated.
    conn.execute("UPDATE drive_sync_state SET enabled = 0, access_token = NULL,
        refresh_token = NULL, token_expires_at = 0 WHERE id = 1", []).unwrap();
    assert_eq!(load_state(&conn).unwrap().account_subject.as_deref(), Some("account-a"));
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute("UPDATE drive_sync_state SET enabled = 1,
        account_subject = 'account-b', account_email = 'b@example.com' WHERE id = 1", []).unwrap();
    assert!(pending_local_plays(&conn).unwrap().is_empty(),
        "account A plays must not upload to B");
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('New', 'Artist', 1700000001000)", []).unwrap();
    let new_id = conn.last_insert_rowid();
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    pin_local_origin(&conn, &old_device, new_id, 1_700_000_001_000,
        "New", "Artist").unwrap();
    let owner: String = conn.query_row(
        "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
        [new_id], |row| row.get(0)
    ).unwrap();
    assert_eq!(owner, "account-b");
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute("UPDATE drive_sync_state SET enabled = 1,
        account_subject = 'account-a', account_email = 'a@example.com' WHERE id = 1", []).unwrap();
    let old_pending = pending_local_plays(&conn).unwrap();
    assert_eq!(old_pending.len(), 1, "returning to A must not upload B plays");
    assert_eq!(old_pending[0].id, old_id);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn explicit_reassignment_reuploads_local_only_without_relaying_imports() {
    let (directory, conn) = history_storage_fixture();
    conn.execute("UPDATE drive_sync_state SET account_subject = 'account-b',
        enabled = 1 WHERE id = 1", []).unwrap();
    let event = fixture_batch().events[0].clone();
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('Old', 'Artist', 1700000000000)", []).unwrap();
    let old_id = conn.last_insert_rowid();
    conn.execute("INSERT INTO drive_event_state
        (scrobble_id, drive_imported, owner_account_subject)
        VALUES (?1, 0, 'account-a')", [old_id]).unwrap();
    assert!(pending_local_plays(&conn).unwrap().is_empty());
    assert!(insert_remote_event(&conn, "remote-device", &event).unwrap());
    let reassigned = authorize_existing_local_history(&conn, "account-b").unwrap();
    assert_eq!(reassigned, 1, "one local-owned play may be explicitly transferred");
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    assert_eq!(pending_local_plays(&conn).unwrap()[0].id, old_id);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}


#[test]
fn interrupted_oauth_keeps_verified_ownership_for_next_account_switch() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'account-a',
         last_verified_account_subject = 'account-a', account_email = 'a@example.com'",
        [],
    ).unwrap();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Old capture', 'Artist', 1700000000000)", [],
    ).unwrap();
    let old_id = conn.last_insert_rowid();
    let owner = load_state(&conn).unwrap().device_id;
    // Simulate a crash after prepare (before receiving the new OAuth identity).
    prepare_oauth_credentials(&conn, false).unwrap();
    assert_eq!(load_state(&conn).unwrap().last_verified_account_subject.as_deref(),
        Some("account-a"));
    assert!(load_state(&conn).unwrap().account_subject.is_none());
    // Next login is B: even though the active account is temporarily null,
    // the last verified A identity must still claim the original history.
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET account_subject = 'account-b',
         last_verified_account_subject = 'account-b', enabled = 1", [],
    ).unwrap();
    assert!(pending_local_plays(&conn).unwrap().is_empty(),
        "previous owner's uncatalogued plays cannot leak to B");
    let tagged: String = conn.query_row(
        "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
        [old_id], |row| row.get(0),
    ).unwrap();
    assert_eq!(tagged, "account-a");
    assert!(!owner.is_empty());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn returning_google_account_preserves_accepted_deletion_and_cursor() {
    let (directory, conn) = history_storage_fixture();
    let deletion_version = 1_700_000_000_100_i64;
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'account-a',
         last_verified_account_subject = 'account-a',
         download_cursor = 4500, accepted_disable_version = ?1 WHERE id = 1",
        [deletion_version],
    ).unwrap();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('After deletion', 'Artist', 1700000000500)", [],
    ).unwrap();
    let new_id = conn.last_insert_rowid();
    conn.execute(
        "INSERT INTO drive_event_state
         (scrobble_id, drive_imported, owner_account_subject, drive_uploaded_at, cloud_suppressed)
         VALUES (?1, 0, 'account-a', 1700000000600, 0)",
        [new_id],
    ).unwrap();

    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'account-b',
         last_verified_account_subject = 'account-b' WHERE id = 1", [],
    ).unwrap();
    restore_account_progress(&conn, "account-b").unwrap();
    assert_eq!((load_state(&conn).unwrap().download_cursor,
                load_state(&conn).unwrap().accepted_disable_version), (0, 0));

    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'account-a',
         last_verified_account_subject = 'account-a' WHERE id = 1", [],
    ).unwrap();
    restore_account_progress(&conn, "account-a").unwrap();
    let state = load_state(&conn).unwrap();
    assert_eq!((state.download_cursor, state.accepted_disable_version),
        (4500, deletion_version));
    assert!(deletion_version <= state.accepted_disable_version,
        "an already accepted deletion must not suppress current-generation plays a second time");
    let suppressed: i64 = conn.query_row(
        "SELECT cloud_suppressed FROM drive_event_state WHERE scrobble_id = ?1",
        [new_id], |row| row.get(0),
    ).unwrap();
    assert_eq!(suppressed, 0, "returning to A must retain its newer uploaded events");
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn interrupted_oauth_must_not_erase_account_progress_snapshot() {
    let (directory, conn) = oauth_storage_fixture();
    prepare_oauth_credentials(&conn, true).unwrap();
    let saved: (i64, i64) = conn.query_row(
        "SELECT download_cursor, accepted_disable_version
         FROM drive_account_progress WHERE account_subject = 'google-stable-123'",
        [], |row| Ok((row.get(0)?, row.get(1)?)),
    ).unwrap();
    assert_eq!(saved, (123, 100));
    // Second attempt starts while the active account is still NULL.
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-stable-123'
         WHERE id = 1", [],
    ).unwrap();
    restore_account_progress(&conn, "google-stable-123").unwrap();
    let state = load_state(&conn).unwrap();
    assert_eq!((state.download_cursor, state.accepted_disable_version), saved);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn existing_active_subject_migrates_to_durable_last_verified_subject() {
    let (directory, conn) = oauth_storage_fixture();
    conn.execute("UPDATE drive_sync_state SET last_verified_account_subject = NULL WHERE id = 1",
        []).unwrap();
    drop(conn);
    let again = open_sync_db(&directory).unwrap();
    assert_eq!(load_state(&again).unwrap().last_verified_account_subject.as_deref(),
        Some("google-stable-123"));
    drop(again);
    std::fs::remove_dir_all(directory).unwrap();
}



#[test]
fn nullable_legacy_metadata_does_not_block_local_export_scanning() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "INSERT INTO scrobbles
         (title, artist, timestamp_utc, album, session_id, volume_level)
         VALUES ('Valid title', 'Valid artist', 1700000000000, NULL, NULL, NULL)",
        [],
    ).unwrap();
    let play = pending_local_plays(&conn).unwrap().pop().unwrap();
    let producer = load_state(&conn).unwrap().device_id;
    assert!(valid_event(&local_to_wire(&producer, &play)));
    assert_eq!(play.album, "");
    assert_eq!(play.session_id, "");
    assert_eq!(play.volume_level, -1.0);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn nullable_legacy_session_does_not_abort_remote_duplicate_scan() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc, session_id)
         VALUES (?1, ?2, ?3, NULL)",
        params![event.title, event.artist, event.timestamp_utc],
    ).unwrap();
    assert!(!insert_remote_event(&conn, "other-source", &event).unwrap());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn invalid_local_play_does_not_block_the_following_page() {
    let (directory, conn) = history_storage_fixture();
    conn.execute_batch(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('', 'Artist', 1700000000000);
         INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Valid', 'Artist', 1700000000001);
         INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Later', 'Artist', 1700000000002);",
    ).unwrap();
    let producer = load_state(&conn).unwrap().device_id;
    let first = pending_local_plays_page(&conn, None, 2).unwrap();
    assert_eq!(first.len(), 2);
    assert!(!valid_event(&local_to_wire(&producer, &first[0])));
    assert!(valid_event(&local_to_wire(&producer, &first[1])));
    let tail = pending_local_plays_page(
        &conn, Some((first[1].timestamp_utc, first[1].id)), 2
    ).unwrap();
    assert_eq!(tail.len(), 1);
    assert!(valid_event(&local_to_wire(&producer, &tail[0])));
    let still_pending: i64 = conn.query_row("SELECT COUNT(*) FROM scrobbles",
        [], |row| row.get(0)).unwrap();
    assert_eq!(still_pending, 3, "invalid local events must never be deleted");
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn oversized_local_metadata_is_rejected_without_mutating_sqlite() {
    let (directory, conn) = history_storage_fixture();
    let title = "A".repeat(1200);
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES (?1, 'Artist', 1700000000000)", [&title]).unwrap();
    let producer = load_state(&conn).unwrap().device_id;
    let pending = pending_local_plays(&conn).unwrap();
    assert_eq!(pending.len(), 1);
    assert!(!valid_event(&local_to_wire(&producer, &pending[0])));
    let persisted: String = conn.query_row(
        "SELECT title FROM scrobbles", [], |row| row.get(0),
    ).unwrap();
    assert_eq!(persisted, title);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn legacy_email_only_sessions_are_disabled_without_losing_cursors() {
    let (directory, conn) = oauth_storage_fixture();
    assert_eq!(load_state(&conn).unwrap().account_subject.as_deref(), Some("google-stable-123"));
    conn.execute("UPDATE drive_sync_state SET account_subject = NULL WHERE id = 1", []).unwrap();
    drop(conn);
    let reopened = open_sync_db(&directory).unwrap();
    let state = load_state(&reopened).unwrap();
    assert!(!state.enabled && state.access_token.is_none());
    assert!(state.account_email.is_none() && state.account_subject.is_none());
    assert_eq!((state.download_cursor, state.accepted_disable_version), (123, 100));
    drop(reopened);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn oauth_callback_requires_the_expected_route_nonce_and_unambiguous_parameters() {
    assert_eq!(parse_oauth_callback("GET /oauth2/callback?state=nonce&code=a%2Bb HTTP/1.1", "nonce").unwrap().unwrap(), "a+b");
    for line in [
        "GET /favicon.ico HTTP/1.1",
        "GET /oauth2/callback?state=wrong&code=x HTTP/1.1",
        "POST /oauth2/callback?state=nonce&code=x HTTP/1.1",
        "GET /oauth2/callback?state=nonce&state=nonce&code=x HTTP/1.1",
        "GET /oauth2/callback?state=nonce&code=x&code=y HTTP/1.1",
        "GET //other/oauth2/callback?state=nonce&code=x HTTP/1.1",
    ] { assert!(parse_oauth_callback(line, "nonce").is_none(), "{line}"); }
    assert!(parse_oauth_callback("GET /oauth2/callback?state=nonce&error=access_denied HTTP/1.1", "nonce").unwrap().is_err());
    assert!(parse_oauth_callback("GET /oauth2/callback?state=nonce&code= HTTP/1.1", "nonce").unwrap().is_err());
}

#[tokio::test]
async fn oauth_listener_ignores_unrelated_connections_and_reads_fragmented_headers() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        wait_for_oauth_callback(&listener, "nonce", Duration::from_secs(5)).await
    });
    for target in ["/favicon.ico", "/oauth2/callback?state=wrong&code=x"] {
        let mut client = tokio::net::TcpStream::connect(address).await.unwrap();
        client.write_all(format!("GET {target} HTTP/1.1\r\nHost: localhost\r\n\r\n").as_bytes()).await.unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        assert!(response.starts_with("HTTP/1.1 400"));
    }
    let mut client = tokio::net::TcpStream::connect(address).await.unwrap();
    client.write_all(b"GET /oauth2/callback?code=a%2Bb").await.unwrap();
    tokio::time::sleep(Duration::from_millis(10)).await;
    client.write_all(b"&state=nonce HTTP/1.1\r\nHost: localhost\r\n\r\n").await.unwrap();
    let mut response = String::new();
    client.read_to_string(&mut response).await.unwrap();
    assert!(response.starts_with("HTTP/1.1 200"));
    assert_eq!(server.await.unwrap().unwrap(), "a+b");
}

#[tokio::test]
async fn oauth_callback_deadline_also_bounds_an_incomplete_connection() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let failure = wait_for_oauth_callback(&listener, "nonce", Duration::from_millis(20)).await.unwrap_err();
    assert!(failure.contains("timed out"));
    drop(client);
}

#[test]
fn duplicate_control_markers_use_the_latest_valid_server_version() {
    let mut earlier = fixture_file(&fixture_batch(), b"fixture");
    earlier.modified_time = Some("2026-10-02T00:00:00Z".into());
    let mut later = earlier.clone();
    later.modified_time = Some("2026-10-02T00:01:00Z".into());
    let expected = parse_time_ms(later.modified_time.as_deref());
    assert_eq!(latest_marker_version(&[earlier.clone(), later.clone()]).unwrap(), expected);
    assert_eq!(latest_marker_version(&[later, earlier.clone()]).unwrap(), expected);
    assert_eq!(latest_marker_version(&[]).unwrap(), 0);
    earlier.modified_time = None;
    assert!(latest_marker_version(&[earlier]).is_err());
}

#[test]
fn retry_requires_complete_upload_identity_not_only_matching_bytes() {
    let batch = fixture_batch();
    let bytes = encode_batch(&batch).unwrap();
    let file = fixture_file(&batch, &bytes);
    assert!(verified_upload(&file, &file.name, &batch.source_device_id, 42, &bytes));
    for key in ["tempo_kind", "tempo_schema", "source_device_id", "source_platform", APP_PROPERTY_GENERATION] {
        let mut wrong = file.clone();
        wrong.app_properties.insert(key.into(), "wrong".into());
        assert!(!verified_upload(&wrong, &file.name, &batch.source_device_id, 42, &bytes), "{key}");
    }
}

#[test]
fn deletion_stop_and_cursors_are_committed_before_cloud_cleanup() {
    let conn = Connection::open_in_memory().unwrap();
    conn.execute_batch(
        "CREATE TABLE scrobbles (id INTEGER PRIMARY KEY, timestamp_utc INTEGER);
         INSERT INTO scrobbles VALUES (1, 100), (2, 100), (3, 100), (4, 300);
         CREATE TABLE drive_event_state (
             scrobble_id INTEGER PRIMARY KEY, drive_imported INTEGER,
             drive_uploaded_at INTEGER, owner_account_subject TEXT,
             cloud_suppressed INTEGER DEFAULT 0
         );
         INSERT INTO drive_event_state VALUES (1, 0, 100, 'google-a', 0),
             (2, 1, 100, 'google-a', 0);
         CREATE TABLE drive_rejected_files (
            account_subject TEXT NOT NULL, file_id TEXT NOT NULL,
            file_name TEXT NOT NULL, rejection_reason TEXT NOT NULL,
            recorded_at INTEGER NOT NULL,
            PRIMARY KEY (account_subject, file_id)
         );
         CREATE TABLE drive_sync_state (
            id INTEGER PRIMARY KEY, enabled INTEGER, account_subject TEXT,
            accepted_disable_version INTEGER, download_cursor INTEGER,
            last_uploaded INTEGER, last_imported INTEGER, last_error TEXT,
            offline_capture_account_subject TEXT
         );
         INSERT INTO drive_sync_state VALUES (1, 1, 'google-a', 0, 123, 4, 5, NULL, NULL);"
    ).unwrap();
    accept_deletion_marker(&conn, 200, Some("remote deletion")).unwrap();
    let state: (i64, i64, i64, i64, i64) = conn.query_row(
        "SELECT enabled, accepted_disable_version, download_cursor, last_uploaded, last_imported FROM drive_sync_state",
        [], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?))
    ).unwrap();
    assert_eq!(state, (0, 200, 0, 0, 0));
    let rows: Vec<(i64, Option<i64>, i64)> = conn.prepare(
        "SELECT scrobble_id, drive_uploaded_at, cloud_suppressed FROM drive_event_state ORDER BY scrobble_id"
    ).unwrap().query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?))).unwrap()
        .collect::<Result<_, _>>().unwrap();
    assert_eq!(rows, vec![(1, Some(100), 1), (2, Some(100), 0), (3, None, 1)]);
}

#[test]
fn real_local_capture_after_remote_deletion_keeps_same_account_ownership() {
    use crate::db::models::{Play, PlayStatus};
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a',
         last_verified_account_subject = 'google-a' WHERE id = 1", [],
    ).unwrap();
    let deleted_at = 1_700_000_000_100_i64;
    accept_deletion_marker(&conn, deleted_at, None).unwrap();
    assert!(!load_state(&conn).unwrap().enabled);
    let db = crate::db::Database::new(&db_path(&directory)).unwrap();
    let play = Play {
        id: None, title: "New offline play".into(), artist: "Artist".into(),
        album: String::new(), duration_ms: 150_000,
        timestamp_utc: deleted_at + 200,
        source_app: "Spotify".into(), status: PlayStatus::Queued,
        listened_ms: 140_000, skipped: false, replay_count: 0,
        is_muted: false, completion_percentage: 93.0,
        pause_count: 0, seek_count: 0, session_id: "new-session".into(),
        site: String::new(), content_type: "MUSIC".into(), volume_level: 0.5,
    };
    let captured_id = db.insert_play(&play).unwrap();
    drop(db);
    let owner: String = conn.query_row(
        "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
        [captured_id], |row| row.get(0),
    ).unwrap();
    assert_eq!(owner, "google-a");
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);

    // Switching to another Google subject cannot claim A's offline play.
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-b',
         last_verified_account_subject = 'google-b' WHERE id = 1",
        [],
    ).unwrap();
    restore_account_progress(&conn, "google-b").unwrap();
    assert!(pending_local_plays(&conn).unwrap().is_empty());

    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a',
         last_verified_account_subject = 'google-a' WHERE id = 1",
        [],
    ).unwrap();
    restore_account_progress(&conn, "google-a").unwrap();
    assert_eq!(
        pending_local_plays(&conn).unwrap().iter().map(|p| p.id).collect::<Vec<_>>(),
        vec![captured_id],
        "returning to A must allow its fresh offline recording to upload"
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn rejected_cloud_batches_remain_visible_until_repaired_or_removed() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET account_subject = 'google-a' WHERE id = 1",
        [],
    ).unwrap();
    let batch = fixture_batch();
    let mut broken = fixture_file(&batch, b"bad");
    broken.id = "bad-file".into();
    broken.name = "tempo_history_bad.json.gz".into();
    mark_rejected_batch(&conn, "google-a", &broken, "Wrong SHA-256").unwrap();
    assert_eq!(count_rejected_batches(&conn).unwrap(), 1);
    drop(conn);
    let reopened = open_sync_db(&directory).unwrap();
    assert_eq!(count_rejected_batches(&reopened).unwrap(), 1,
        "a download cursor checkpoint cannot silently erase the warning");
    mark_rejected_batch(&reopened, "google-b", &broken, "Other account").unwrap();
    assert_eq!(count_rejected_batches(&reopened).unwrap(), 1,
        "a different Google account cannot affect this account's warning");
    let example = sample_rejected_batch(&reopened).unwrap().unwrap();
    assert_eq!(example, (broken.name.clone(), "Wrong SHA-256".into()));
    clear_rejected_batch(&reopened, "google-a", &broken.id).unwrap();
    assert_eq!(count_rejected_batches(&reopened).unwrap(), 0);
    mark_rejected_batch(&reopened, "google-a", &broken, "Wrong SHA-256").unwrap();
    clear_missing_rejected_batches(&reopened, "google-a", &[]).unwrap();
    assert_eq!(count_rejected_batches(&reopened).unwrap(), 0,
        "full scan clears warnings for batches removed from Drive");
    drop(reopened);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn deleted_cloud_archive_requires_explicit_opt_in_to_republish() {
    let (directory, conn) = history_storage_fixture();
    conn.execute("UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a' WHERE id = 1", []).unwrap();
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Earlier play', 'Artist', 1700000000000)", []).unwrap();
    let old_id = conn.last_insert_rowid();
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    conn.execute("INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Offline newer play', 'Artist', 1700000000500)", []).unwrap();
    let new_id = conn.last_insert_rowid();
    accept_deletion_marker(&conn, 1_700_000_000_100, None).unwrap();
    conn.execute("UPDATE drive_sync_state SET enabled = 1 WHERE id = 1", []).unwrap();
    assert_eq!(
        pending_local_plays(&conn).unwrap().iter().map(|play| play.id).collect::<Vec<_>>(),
        vec![new_id],
        "an offline play captured after deletion must remain eligible"
    );
    assert!(lan_play_account_owners(&directory, &[old_id]).unwrap().is_empty(),
        "LAN must not relay a cloud-suppressed event back to Android Drive");
    assert_eq!(authorize_existing_local_history(&conn, "google-a").unwrap(), 2);
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 2);
    assert_eq!(pending_local_plays(&conn).unwrap()[0].id, old_id);
    assert_eq!(lan_play_account_owners(&directory, &[old_id]).unwrap().get(&old_id),
        Some(&"google-a".to_string()));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}



fn fixture_batch() -> WireBatch {
    let event = WireEvent {
        event_id: "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4".into(),
        title: "Song".into(), artist: "Artist".into(), album: None,
        timestamp_utc: 1_700_000_000_000, duration_ms: 180_000, listened_ms: 170_000,
        source_app: "Spotify".into(), source: "desktop:Spotify".into(),
        skipped: false, replay_count: 0, completion_percentage: 94, pause_count: 0, seek_count: 0,
        session_id: None, site: None, content_type: "MUSIC".into(), volume_level: Some(50),
        total_pause_duration_ms: 0, position_updates_count: 0,
    };
    WireBatch {
        schema_version: 1, batch_id: batch_id(std::slice::from_ref(&event)), source_device_id: "device-1".into(),
        source_device_name: "Tempo Desktop".into(), source_platform: "desktop".into(),
        created_at_utc: 1_700_000_000_000, events: vec![event],
    }
}

fn fixture_file(batch: &WireBatch, bytes: &[u8]) -> DriveFileRecord {
    DriveFileRecord {
        id: "file-1".into(), name: batch_file_name(42, &batch.source_device_id, &batch.batch_id),
        size: Some(bytes.len().to_string()), created_time: None, modified_time: None,
        app_properties: HashMap::from([
            ("tempo_kind".into(), "history_batch".into()),
            ("tempo_schema".into(), "1".into()),
            ("source_device_id".into(), batch.source_device_id.clone()),
            ("source_platform".into(), batch.source_platform.clone()),
            (APP_PROPERTY_GENERATION.into(), "42".into()),
            (APP_PROPERTY_SHA256.into(), hex::encode(Sha256::digest(bytes))),
        ]),
    }
}

fn compress_json(value: &serde_json::Value) -> Vec<u8> {
    let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(&serde_json::to_vec(value).unwrap()).unwrap();
    encoder.finish().unwrap()
}

#[test]
fn prototype_uploads_are_requeued_once_without_reuploading_imports() {
    let conn = Connection::open_in_memory().unwrap();
    conn.execute_batch(
        "CREATE TABLE drive_sync_migrations (name TEXT PRIMARY KEY);
         CREATE TABLE drive_event_state (
             scrobble_id INTEGER PRIMARY KEY, drive_imported INTEGER, drive_uploaded_at INTEGER
         );
         INSERT INTO drive_event_state VALUES (1, 0, 100), (2, 1, 100);"
    ).unwrap();
    requeue_unverified_prototype_uploads(&conn).unwrap();
    let timestamps: Vec<Option<i64>> = conn.prepare(
        "SELECT drive_uploaded_at FROM drive_event_state ORDER BY scrobble_id"
    ).unwrap().query_map([], |row| row.get(0)).unwrap()
        .collect::<Result<_, _>>().unwrap();
    assert_eq!(timestamps, vec![None, Some(100)]);
    conn.execute("UPDATE drive_event_state SET drive_uploaded_at = 200 WHERE scrobble_id = 1", []).unwrap();
    requeue_unverified_prototype_uploads(&conn).unwrap();
    let timestamp: Option<i64> = conn.query_row(
        "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1", [], |row| row.get(0)
    ).unwrap();
    assert_eq!(timestamp, Some(200));
}

#[test]
fn protocol_batch_round_trip_and_integrity_metadata() {
    let batch = fixture_batch();
    let bytes = encode_batch(&batch).unwrap();
    let decoded = decode_batch(&bytes).unwrap();
    assert_eq!(decoded.batch_id, batch.batch_id);
    let file = fixture_file(&batch, &bytes);
    assert!(matches_batch_metadata(&file, &decoded));
    assert!(validate_compressed_metadata(&file, &bytes).is_ok());
    let mut corrupt = bytes.clone();
    corrupt[0] ^= 1;
    assert!(validate_compressed_metadata(&file, &corrupt).is_err());
    let mut wrong_size = file.clone();
    wrong_size.size = Some((bytes.len() + 1).to_string());
    assert!(validate_compressed_metadata(&wrong_size, &bytes).is_err());
    let mut missing_hash = file;
    missing_hash.app_properties.remove(APP_PROPERTY_SHA256);
    assert!(validate_compressed_metadata(&missing_hash, &bytes).is_err());
}

#[test]
fn payload_identity_must_match_drive_filename_and_properties() {
    let batch = fixture_batch();
    let bytes = encode_batch(&batch).unwrap();
    for property in ["source_device_id", "source_platform", "tempo_schema", "tempo_kind"] {
        let mut file = fixture_file(&batch, &bytes);
        file.app_properties.insert(property.into(), "wrong".into());
        assert!(!matches_batch_metadata(&file, &batch), "{property}");
    }
    let mut file = fixture_file(&batch, &bytes);
    file.name = batch_file_name(43, &batch.source_device_id, &batch.batch_id);
    assert!(!matches_batch_metadata(&file, &batch));
    for generation in ["-1", "+42", "", "NaN", "9007199254740992"] {
        file.app_properties.insert(APP_PROPERTY_GENERATION.into(), generation.into());
        assert_eq!(batch_generation(&file), None, "{generation}");
    }
}

#[test]
fn malformed_batches_are_rejected_before_import() {
    let original = serde_json::to_value(fixture_batch()).unwrap();
    let mutations = [
        ("/batch_id", serde_json::json!("0".repeat(64))),
        ("/source_device_id", serde_json::json!("device/invalid")),
        ("/created_at_utc", serde_json::json!(MAX_WIRE_INTEGER + 1)),
        ("/events/0/event_id", serde_json::json!("not-a-hash")),
        ("/events/0/duration_ms", serde_json::json!(-1)),
        ("/events/0/listened_ms", serde_json::json!(12.5)),
        ("/events/0/replay_count", serde_json::json!(i32::MAX as i64 + 1)),
        ("/events/0/volume_level", serde_json::json!(101)),
        ("/events/0/title", serde_json::json!(" ")),
        ("/events/0/album", serde_json::json!("x".repeat(1001))),
    ];
    for (pointer, value) in mutations {
        let mut malformed = original.clone();
        *malformed.pointer_mut(pointer).unwrap() = value;
        assert!(decode_batch(&compress_json(&malformed)).is_err(), "{pointer}");
    }
    for field in ["album", "session_id", "site", "volume_level"] {
        let mut malformed = original.clone();
        malformed["events"][0].as_object_mut().unwrap().remove(field);
        assert!(decode_batch(&compress_json(&malformed)).is_err(), "missing {field}");
    }
    let mut empty = fixture_batch();
    empty.events.clear();
    assert!(encode_batch(&empty).is_err());
    let mut oversized = fixture_batch();
    oversized.events = vec![oversized.events[0].clone(); 1001];
    oversized.batch_id = batch_id(&oversized.events);
    assert!(encode_batch(&oversized).is_err());
}

#[test]
fn oversized_compressed_and_expanded_payloads_are_rejected() {
    assert!(decode_batch(&vec![0; MAX_BATCH_BYTES + 1]).is_err());
    let expanded = serde_json::json!("x".repeat(MAX_BATCH_BYTES + 1));
    assert!(decode_batch(&compress_json(&expanded)).is_err());
}

#[test]
fn lan_provenance_matches_drive_wire_identity() {
    let (dir, conn) = history_storage_fixture();
    let expected_device = load_state(&conn).unwrap().device_id;
    drop(conn);
    let (actual_device, known) = lan_origin_metadata(&dir, &[]).unwrap();
    assert!(known.is_empty());
    assert_eq!(actual_device, expected_device);
    assert_eq!(
        lan_play_origin("device-1", 42, 1_700_000_000_000, " Song ", " Artist "),
        "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4"
    );
}

#[test]
fn lan_first_identity_is_pinned_before_google_drive_upload() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Old title', 'Artist', 1700000000000)", [],
    ).unwrap();
    let scrobble_id = conn.last_insert_rowid();
    let producer = load_state(&conn).unwrap().device_id;
    drop(conn);

    let (_, first) = persist_lan_origin_metadata(
        &directory,
        &[(scrobble_id, 1_700_000_000_000, "Old title".into(), "Artist".into())],
    ).unwrap();
    let origin = first[&scrobble_id].clone();
    let conn = open_sync_db(&directory).unwrap();
    let uploaded: Option<i64> = conn.query_row(
        "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = ?1",
        [scrobble_id], |row| row.get(0),
    ).unwrap();
    assert_eq!(uploaded, None, "sending via LAN must not mark a Drive upload");
    conn.execute(
        "UPDATE scrobbles SET title = 'Corrected title' WHERE id = ?1",
        [scrobble_id],
    ).unwrap();
    drop(conn);

    let (again_device, later) = persist_lan_origin_metadata(
        &directory,
        &[(scrobble_id, 1_700_000_000_000, "Corrected title".into(), "Artist".into())],
    ).unwrap();
    assert_eq!(producer, again_device);
    assert_eq!(later[&scrobble_id], origin, "LAN retries must keep the first ID");
    let conn = open_sync_db(&directory).unwrap();
    let pending = pending_local_plays(&conn).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(event_id(&producer, &pending[0]), origin,
        "subsequent Drive uploads must reuse the LAN-first origin ID");
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn pinned_drive_origin_survives_a_crash_before_server_acknowledgment() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Track title', 'Performer', 1700000000000)", [],
    ).unwrap();
    let id = conn.last_insert_rowid();
    let producer = load_state(&conn).unwrap().device_id;
    let original = pin_local_origin(
        &conn, &producer, id, 1_700_000_000_000, "Track title", "Performer"
    ).unwrap();
    drop(conn);
    let reopened = open_sync_db(&directory).unwrap();
    let later = pin_local_origin(
        &reopened, &producer, id, 1_700_000_000_000, "Renamed", "Performer"
    ).unwrap();
    assert_eq!(original, later);
    assert_eq!(pending_local_plays(&reopened).unwrap().len(), 1,
        "pinning the ID must not mark a pending Drive upload as completed");
    drop(reopened);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn previously_uploaded_origin_is_stable_after_track_metadata_changes() {
    let (directory, conn) = history_storage_fixture();
    let id = conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Original', 'Artist', 1700000000000)", [],
    ).unwrap();
    assert_eq!(id, 1);
    let scrobble_id = conn.last_insert_rowid();
    let first_identity = lan_play_origin("test-device", scrobble_id, 1_700_000_000_000,
        "Original", "Artist");
    conn.execute(
        "INSERT INTO drive_event_state
         (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at)
         VALUES (?1, ?2, 'test-device', 0, NULL)",
        params![scrobble_id, first_identity],
    ).unwrap();
    conn.execute("UPDATE scrobbles SET title = 'Corrected title' WHERE id = ?1",
        [scrobble_id]).unwrap();
    let (_, known) = lan_origin_metadata(&directory, &[scrobble_id]).unwrap();
    assert_eq!(known.get(&scrobble_id), Some(&first_identity),
        "LAN must reuse the original Drive identity after metadata correction");
    let pending = pending_local_plays(&conn).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(event_id("test-device", &pending[0]), first_identity,
        "a metadata correction must not change a previously uploaded event ID");
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn stable_event_and_batch_ids_match_protocol_shape() {
    let play = LocalPlay {
        id: 42,
        origin_event_id: None,
        title: " Song ".to_string(),
        artist: " Artist ".to_string(),
        album: String::new(),
        duration_ms: 180_000,
        timestamp_utc: 1_700_000_000_000,
        source_app: "Spotify".to_string(),
        listened_ms: 170_000,
        skipped: false,
        replay_count: 0,
        is_muted: false,
        completion_percentage: 94.0,
        pause_count: 1,
        seek_count: 0,
        session_id: "session".to_string(),
        site: String::new(),
        content_type: "MUSIC".to_string(),
        volume_level: 0.5,
    };
    let first = event_id("device-1", &play);
    let second = event_id("device-1", &play);
    assert_eq!(first, second);
    assert_eq!(
        first,
        "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4"
    );
    let wire = local_to_wire("device-1", &play);
    let batch = batch_id(std::slice::from_ref(&wire));
    assert_eq!(batch, batch_id(&[wire]));
    assert_eq!(
        batch,
        "785b57b5c9e86c35176a413093df3c9fce37eb266c70485a0f9e8fff66e95d43"
    );
}

#[test]
fn generation_filename_and_legacy_metadata_are_compatible() {
    assert_eq!(
        batch_file_name(1234, "device-1", "batch-1"),
        "tempo_history_v1_g1234_device-1_batch-1.json.gz"
    );
    let legacy = DriveFileRecord {
        id: "legacy".to_string(),
        name: "tempo_history_v1_device_batch.json.gz".to_string(),
        size: None,
        created_time: None,
        modified_time: None,
        app_properties: HashMap::new(),
    };
    assert_eq!(batch_generation(&legacy), Some(0));
}


#[test]
fn user_delete_cleans_invalid_generations_but_stale_client_cannot() {
    let mut file = fixture_file(&fixture_batch(), b"fixture");
    file.name = batch_file_name(10, "device-1", &"a".repeat(64));
    file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "10".into());
    assert!(should_delete_history_batch(&file, 20, false));
    file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "20".into());
    assert!(!should_delete_history_batch(&file, 20, true));
    file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "21".into());
    assert!(!should_delete_history_batch(&file, 20, true));
    file.app_properties.insert(APP_PROPERTY_GENERATION.into(), "invalid".into());
    assert!(!should_delete_history_batch(&file, 20, false));
    assert!(should_delete_history_batch(&file, 20, true));
    file.name = "tempo_history_v1_corrupt-without-extension".into();
    assert!(should_delete_history_batch(&file, 20, true));
    assert!(!should_delete_history_batch(&file, 20, false));
    file.name = "unrelated_tempo_history_v1_file.json.gz".into();
    assert!(!should_delete_history_batch(&file, 20, true));
}

#[tokio::test]
async fn transient_drive_response_retries_then_succeeds() {
    // Loopback stub exercises the real reqwest request and response code.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/test", listener.local_addr().unwrap());
    let server = tokio::spawn(async move {
        for status in ["503 Service Unavailable", "200 OK"] {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = [0u8; 1024];
            let n = socket.read(&mut request).await.unwrap();
            assert!(n > 0);
            let response = format!(
                "HTTP/1.1 {status}\r\nRetry-After: 0\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}"
            );
            socket.write_all(response.as_bytes()).await.unwrap();
        }
    });
    let response = send_drive_idempotent(http_client().unwrap().get(url))
        .await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    server.await.unwrap();
}

#[test]
fn retries_only_transient_drive_status_codes() {
    assert!(transient_drive_response(StatusCode::TOO_MANY_REQUESTS));
    assert!(transient_drive_response(StatusCode::SERVICE_UNAVAILABLE));
    assert!(transient_drive_response(StatusCode::GATEWAY_TIMEOUT));
    assert!(!transient_drive_response(StatusCode::BAD_REQUEST));
    assert!(!transient_drive_response(StatusCode::FORBIDDEN));
    assert!(!transient_drive_response(StatusCode::UNAUTHORIZED));
}
#[test]
fn protocol_volume_uses_android_percent_scale() {
    let mut play = LocalPlay {
        id: 1,
        origin_event_id: None,
        title: "Song".to_string(),
        artist: "Artist".to_string(),
        album: String::new(),
        duration_ms: 0,
        timestamp_utc: 1,
        source_app: "test".to_string(),
        listened_ms: 0,
        skipped: false,
        replay_count: 0,
        is_muted: false,
        completion_percentage: 0.0,
        pause_count: 0,
        seek_count: 0,
        session_id: String::new(),
        site: String::new(),
        content_type: "MUSIC".to_string(),
        volume_level: 0.42,
    };
    assert_eq!(protocol_volume(&play), Some(42));
    for (volume, expected) in [(0.0, Some(0)), (0.001, Some(1)), (0.01, Some(1)),
        (0.5, Some(50)), (1.0, Some(100)), (1.5, Some(100)), (-1.0, None), (f64::NAN, None)] {
        play.volume_level = volume;
        assert_eq!(protocol_volume(&play), expected, "volume={volume}");
    }
    play.is_muted = true;
    assert_eq!(protocol_volume(&play), Some(0));
}

