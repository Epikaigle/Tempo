//! Account-boundary, OAuth and deletion regression tests.
use super::*;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[test]
fn explicit_reassignment_reuploads_local_only_without_relaying_imports() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET account_subject = 'account-b',
        enabled = 1 WHERE id = 1",
        [],
    )
    .unwrap();
    let event = fixture_batch().events[0].clone();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('Old', 'Artist', 1700000000000)",
        [],
    )
    .unwrap();
    let old_id = conn.last_insert_rowid();
    conn.execute(
        "INSERT INTO drive_event_state
        (scrobble_id, drive_imported, owner_account_subject)
        VALUES (?1, 0, 'account-a')",
        [old_id],
    )
    .unwrap();
    assert!(pending_local_plays(&conn).unwrap().is_empty());
    assert!(insert_remote_event(&conn, "remote-device", &event).unwrap());
    let reassigned = authorize_existing_local_history(&conn, "account-b").unwrap();
    assert_eq!(
        reassigned, 1,
        "one local-owned play may be explicitly transferred"
    );
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
    )
    .unwrap();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Old capture', 'Artist', 1700000000000)",
        [],
    )
    .unwrap();
    let old_id = conn.last_insert_rowid();
    let owner = load_state(&conn).unwrap().device_id;
    // Simulate a crash after prepare (before receiving the new OAuth identity).
    prepare_oauth_credentials(&conn, false).unwrap();
    assert_eq!(
        load_state(&conn)
            .unwrap()
            .last_verified_account_subject
            .as_deref(),
        Some("account-a")
    );
    assert!(load_state(&conn).unwrap().account_subject.is_none());
    // Next login is B: even though the active account is temporarily null,
    // the last verified A identity must still claim the original history.
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET account_subject = 'account-b',
         last_verified_account_subject = 'account-b', enabled = 1",
        [],
    )
    .unwrap();
    assert!(
        pending_local_plays(&conn).unwrap().is_empty(),
        "previous owner's uncatalogued plays cannot leak to B"
    );
    let tagged: String = conn
        .query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
            [old_id],
            |row| row.get(0),
        )
        .unwrap();
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
    )
    .unwrap();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('After deletion', 'Artist', 1700000000500)",
        [],
    )
    .unwrap();
    let new_id = conn.last_insert_rowid();
    conn.execute(
        "INSERT INTO drive_event_state
         (scrobble_id, drive_imported, owner_account_subject, drive_uploaded_at, cloud_suppressed)
         VALUES (?1, 0, 'account-a', 1700000000600, 0)",
        [new_id],
    )
    .unwrap();

    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'account-b',
         last_verified_account_subject = 'account-b' WHERE id = 1",
        [],
    )
    .unwrap();
    restore_account_progress(&conn, "account-b").unwrap();
    assert_eq!(
        (
            load_state(&conn).unwrap().download_cursor,
            load_state(&conn).unwrap().accepted_disable_version
        ),
        (0, 0)
    );

    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'account-a',
         last_verified_account_subject = 'account-a' WHERE id = 1",
        [],
    )
    .unwrap();
    restore_account_progress(&conn, "account-a").unwrap();
    let state = load_state(&conn).unwrap();
    assert_eq!(
        (state.download_cursor, state.accepted_disable_version),
        (4500, deletion_version)
    );
    assert!(
        deletion_version <= state.accepted_disable_version,
        "an already accepted deletion must not suppress current-generation plays a second time"
    );
    let suppressed: i64 = conn
        .query_row(
            "SELECT cloud_suppressed FROM drive_event_state WHERE scrobble_id = ?1",
            [new_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(
        suppressed, 0,
        "returning to A must retain its newer uploaded events"
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn interrupted_oauth_must_not_erase_account_progress_snapshot() {
    let (directory, conn) = oauth_storage_fixture();
    prepare_oauth_credentials(&conn, true).unwrap();
    let saved: (i64, i64) = conn
        .query_row(
            "SELECT download_cursor, accepted_disable_version
         FROM drive_account_progress WHERE account_subject = 'google-stable-123'",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(saved, (123, 100));
    // Second attempt starts while the active account is still NULL.
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-stable-123'
         WHERE id = 1",
        [],
    )
    .unwrap();
    restore_account_progress(&conn, "google-stable-123").unwrap();
    let state = load_state(&conn).unwrap();
    assert_eq!(
        (state.download_cursor, state.accepted_disable_version),
        saved
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn existing_active_subject_migrates_to_durable_last_verified_subject() {
    let (directory, conn) = oauth_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET last_verified_account_subject = NULL WHERE id = 1",
        [],
    )
    .unwrap();
    drop(conn);
    let again = open_sync_db(&directory).unwrap();
    assert_eq!(
        load_state(&again)
            .unwrap()
            .last_verified_account_subject
            .as_deref(),
        Some("google-stable-123")
    );
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
    )
    .unwrap();
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
    )
    .unwrap();
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
    )
    .unwrap();
    let producer = load_state(&conn).unwrap().device_id;
    let first = pending_local_plays_page(&conn, None, 2).unwrap();
    assert_eq!(first.len(), 2);
    assert!(!valid_event(&local_to_wire(&producer, &first[0])));
    assert!(valid_event(&local_to_wire(&producer, &first[1])));
    let tail =
        pending_local_plays_page(&conn, Some((first[1].timestamp_utc, first[1].id)), 2).unwrap();
    assert_eq!(tail.len(), 1);
    assert!(valid_event(&local_to_wire(&producer, &tail[0])));
    let still_pending: i64 = conn
        .query_row("SELECT COUNT(*) FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    assert_eq!(
        still_pending, 3,
        "invalid local events must never be deleted"
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn oversized_local_metadata_is_rejected_without_mutating_sqlite() {
    let (directory, conn) = history_storage_fixture();
    let title = "A".repeat(1200);
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES (?1, 'Artist', 1700000000000)",
        [&title],
    )
    .unwrap();
    let producer = load_state(&conn).unwrap().device_id;
    let pending = pending_local_plays(&conn).unwrap();
    assert_eq!(pending.len(), 1);
    assert!(!valid_event(&local_to_wire(&producer, &pending[0])));
    let persisted: String = conn
        .query_row("SELECT title FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    assert_eq!(persisted, title);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn legacy_email_only_sessions_are_disabled_without_losing_cursors() {
    let (directory, conn) = oauth_storage_fixture();
    assert_eq!(
        load_state(&conn).unwrap().account_subject.as_deref(),
        Some("google-stable-123")
    );
    conn.execute(
        "UPDATE drive_sync_state SET account_subject = NULL WHERE id = 1",
        [],
    )
    .unwrap();
    drop(conn);
    let reopened = open_sync_db(&directory).unwrap();
    let state = load_state(&reopened).unwrap();
    assert!(!state.enabled && state.access_token.is_none());
    assert!(state.account_email.is_none() && state.account_subject.is_none());
    assert_eq!(
        (state.download_cursor, state.accepted_disable_version),
        (123, 100)
    );
    drop(reopened);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn oauth_callback_requires_the_expected_route_nonce_and_unambiguous_parameters() {
    assert_eq!(
        parse_oauth_callback(
            "GET /oauth2/callback?state=nonce&code=a%2Bb HTTP/1.1",
            "nonce"
        )
        .unwrap()
        .unwrap(),
        "a+b"
    );
    for line in [
        "GET /favicon.ico HTTP/1.1",
        "GET /oauth2/callback?state=wrong&code=x HTTP/1.1",
        "POST /oauth2/callback?state=nonce&code=x HTTP/1.1",
        "GET /oauth2/callback?state=nonce&state=nonce&code=x HTTP/1.1",
        "GET /oauth2/callback?state=nonce&code=x&code=y HTTP/1.1",
        "GET //other/oauth2/callback?state=nonce&code=x HTTP/1.1",
    ] {
        assert!(parse_oauth_callback(line, "nonce").is_none(), "{line}");
    }
    assert!(parse_oauth_callback(
        "GET /oauth2/callback?state=nonce&error=access_denied HTTP/1.1",
        "nonce"
    )
    .unwrap()
    .is_err());
    assert!(
        parse_oauth_callback("GET /oauth2/callback?state=nonce&code= HTTP/1.1", "nonce")
            .unwrap()
            .is_err()
    );
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
        client
            .write_all(format!("GET {target} HTTP/1.1\r\nHost: localhost\r\n\r\n").as_bytes())
            .await
            .unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).await.unwrap();
        assert!(response.starts_with("HTTP/1.1 400"));
    }
    let mut client = tokio::net::TcpStream::connect(address).await.unwrap();
    client
        .write_all(b"GET /oauth2/callback?code=a%2Bb")
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(10)).await;
    client
        .write_all(b"&state=nonce HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .await
        .unwrap();
    let mut response = String::new();
    client.read_to_string(&mut response).await.unwrap();
    assert!(response.starts_with("HTTP/1.1 200"));
    assert_eq!(server.await.unwrap().unwrap(), "a+b");
}

#[tokio::test]
async fn oauth_callback_deadline_also_bounds_an_incomplete_connection() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let failure = wait_for_oauth_callback(&listener, "nonce", Duration::from_millis(20))
        .await
        .unwrap_err();
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
    assert_eq!(
        latest_marker_version(&[earlier.clone(), later.clone()]).unwrap(),
        expected
    );
    assert_eq!(
        latest_marker_version(&[later, earlier.clone()]).unwrap(),
        expected
    );
    assert_eq!(latest_marker_version(&[]).unwrap(), 0);
    earlier.modified_time = None;
    assert!(latest_marker_version(&[earlier]).is_err());
}

#[test]
fn retry_requires_complete_upload_identity_not_only_matching_bytes() {
    let batch = fixture_batch();
    let bytes = encode_batch(&batch).unwrap();
    let file = fixture_file(&batch, &bytes);
    assert!(verified_upload(
        &file,
        &file.name,
        &batch.source_device_id,
        42,
        &bytes
    ));
    for key in [
        "tempo_kind",
        "tempo_schema",
        "source_device_id",
        "source_platform",
        APP_PROPERTY_GENERATION,
    ] {
        let mut wrong = file.clone();
        wrong.app_properties.insert(key.into(), "wrong".into());
        assert!(
            !verified_upload(&wrong, &file.name, &batch.source_device_id, 42, &bytes),
            "{key}"
        );
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
         INSERT INTO drive_sync_state VALUES (1, 1, 'google-a', 0, 123, 4, 5, NULL, NULL);",
    )
    .unwrap();
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
    assert_eq!(
        rows,
        vec![(1, Some(100), 1), (2, Some(100), 0), (3, None, 1)]
    );
}

#[test]
fn real_local_capture_after_remote_deletion_keeps_same_account_ownership() {
    use crate::db::models::{Play, PlayStatus};
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a',
         last_verified_account_subject = 'google-a' WHERE id = 1",
        [],
    )
    .unwrap();
    let deleted_at = 1_700_000_000_100_i64;
    accept_deletion_marker(&conn, deleted_at, None).unwrap();
    assert!(!load_state(&conn).unwrap().enabled);
    let db = crate::db::Database::new(&db_path(&directory)).unwrap();
    let play = Play {
        id: None,
        title: "New offline play".into(),
        artist: "Artist".into(),
        album: String::new(),
        duration_ms: 150_000,
        timestamp_utc: deleted_at + 200,
        source_app: "Spotify".into(),
        status: PlayStatus::Queued,
        listened_ms: 140_000,
        skipped: false,
        replay_count: 0,
        is_muted: false,
        completion_percentage: 93.0,
        pause_count: 0,
        seek_count: 0,
        session_id: "new-session".into(),
        site: String::new(),
        content_type: "MUSIC".into(),
        volume_level: 0.5,
    };
    let captured_id = db.insert_play(&play).unwrap();
    drop(db);
    let owner: String = conn
        .query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
            [captured_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(owner, "google-a");
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);

    // Switching to another Google subject cannot claim A's offline play.
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-b',
         last_verified_account_subject = 'google-b' WHERE id = 1",
        [],
    )
    .unwrap();
    restore_account_progress(&conn, "google-b").unwrap();
    assert!(pending_local_plays(&conn).unwrap().is_empty());

    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a',
         last_verified_account_subject = 'google-a' WHERE id = 1",
        [],
    )
    .unwrap();
    restore_account_progress(&conn, "google-a").unwrap();
    assert_eq!(
        pending_local_plays(&conn)
            .unwrap()
            .iter()
            .map(|p| p.id)
            .collect::<Vec<_>>(),
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
    )
    .unwrap();
    let batch = fixture_batch();
    let mut broken = fixture_file(&batch, b"bad");
    broken.id = "bad-file".into();
    broken.name = "tempo_history_bad.json.gz".into();
    mark_rejected_batch(&conn, "google-a", &broken, "Wrong SHA-256").unwrap();
    assert_eq!(count_rejected_batches(&conn).unwrap(), 1);
    drop(conn);
    let reopened = open_sync_db(&directory).unwrap();
    assert_eq!(
        count_rejected_batches(&reopened).unwrap(),
        1,
        "a download cursor checkpoint cannot silently erase the warning"
    );
    mark_rejected_batch(&reopened, "google-b", &broken, "Other account").unwrap();
    assert_eq!(
        count_rejected_batches(&reopened).unwrap(),
        1,
        "a different Google account cannot affect this account's warning"
    );
    let example = sample_rejected_batch(&reopened).unwrap().unwrap();
    assert_eq!(example, (broken.name.clone(), "Wrong SHA-256".into()));
    clear_rejected_batch(&reopened, "google-a", &broken.id).unwrap();
    assert_eq!(count_rejected_batches(&reopened).unwrap(), 0);
    mark_rejected_batch(&reopened, "google-a", &broken, "Wrong SHA-256").unwrap();
    clear_missing_rejected_batches(&reopened, "google-a", &[]).unwrap();
    assert_eq!(
        count_rejected_batches(&reopened).unwrap(),
        0,
        "full scan clears warnings for batches removed from Drive"
    );
    drop(reopened);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn failed_stale_cloud_cleanup_is_account_scoped_and_remains_visible() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET account_subject = 'google-a' WHERE id = 1",
        [],
    )
    .unwrap();
    let mut file = fixture_file(&fixture_batch(), b"obsolete");
    file.id = "undeleted-stale-file".into();
    file.name = "tempo_history_v1_g1_old_device.json.gz".into();

    mark_rejected_batch(
        &conn,
        "google-a",
        &file,
        "Obsolete pre-deletion Drive file could not be removed",
    )
    .unwrap();
    assert_eq!(count_rejected_batches(&conn).unwrap(), 1);
    drop(conn);

    let reopened = open_sync_db(&directory).unwrap();
    assert_eq!(
        sample_rejected_batch(&reopened).unwrap(),
        Some((
            file.name.clone(),
            "Obsolete pre-deletion Drive file could not be removed".into(),
        )),
        "a background cleanup failure must remain visible across restarts"
    );
    mark_rejected_batch(&reopened, "google-b", &file, "Other Google account").unwrap();
    assert_eq!(count_rejected_batches(&reopened).unwrap(), 1);
    clear_missing_rejected_batches(&reopened, "google-a", &[file.clone()]).unwrap();
    assert_eq!(
        count_rejected_batches(&reopened).unwrap(),
        1,
        "an existing stale file still needs a cleanup warning"
    );
    clear_missing_rejected_batches(&reopened, "google-a", &[]).unwrap();
    assert_eq!(
        count_rejected_batches(&reopened).unwrap(),
        0,
        "a complete Drive listing can retire warnings for removed files"
    );
    drop(reopened);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn deleted_cloud_archive_requires_explicit_opt_in_to_republish() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a' WHERE id = 1",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Earlier play', 'Artist', 1700000000000)",
        [],
    )
    .unwrap();
    let old_id = conn.last_insert_rowid();
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Offline newer play', 'Artist', 1700000000500)",
        [],
    )
    .unwrap();
    let new_id = conn.last_insert_rowid();
    accept_deletion_marker(&conn, 1_700_000_000_100, None).unwrap();
    conn.execute("UPDATE drive_sync_state SET enabled = 1 WHERE id = 1", [])
        .unwrap();
    assert_eq!(
        pending_local_plays(&conn)
            .unwrap()
            .iter()
            .map(|play| play.id)
            .collect::<Vec<_>>(),
        vec![new_id],
        "an offline play captured after deletion must remain eligible"
    );
    assert!(
        lan_play_account_owners(&directory, &[old_id])
            .unwrap()
            .is_empty(),
        "LAN must not relay a cloud-suppressed event back to Android Drive"
    );
    assert_eq!(
        authorize_existing_local_history(&conn, "google-a").unwrap(),
        2
    );
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 2);
    assert_eq!(pending_local_plays(&conn).unwrap()[0].id, old_id);
    assert_eq!(
        lan_play_account_owners(&directory, &[old_id])
            .unwrap()
            .get(&old_id),
        Some(&"google-a".to_string())
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

