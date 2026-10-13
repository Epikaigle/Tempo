// Protocol, provenance and durability regression tests.
use super::*;

#[test]
fn prototype_uploads_are_requeued_once_without_reuploading_imports() {
    let conn = Connection::open_in_memory().unwrap();
    conn.execute_batch(
        "CREATE TABLE drive_sync_migrations (name TEXT PRIMARY KEY);
         CREATE TABLE drive_event_state (
             scrobble_id INTEGER PRIMARY KEY, drive_imported INTEGER, drive_uploaded_at INTEGER
         );
         INSERT INTO drive_event_state VALUES (1, 0, 100), (2, 1, 100);",
    )
    .unwrap();
    requeue_unverified_prototype_uploads(&conn).unwrap();
    let timestamps: Vec<Option<i64>> = conn
        .prepare("SELECT drive_uploaded_at FROM drive_event_state ORDER BY scrobble_id")
        .unwrap()
        .query_map([], |row| row.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert_eq!(timestamps, vec![None, Some(100)]);
    conn.execute(
        "UPDATE drive_event_state SET drive_uploaded_at = 200 WHERE scrobble_id = 1",
        [],
    )
    .unwrap();
    requeue_unverified_prototype_uploads(&conn).unwrap();
    let timestamp: Option<i64> = conn
        .query_row(
            "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1",
            [],
            |row| row.get(0),
        )
        .unwrap();
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
    for property in [
        "source_device_id",
        "source_platform",
        "tempo_schema",
        "tempo_kind",
    ] {
        let mut file = fixture_file(&batch, &bytes);
        file.app_properties.insert(property.into(), "wrong".into());
        assert!(!matches_batch_metadata(&file, &batch), "{property}");
    }
    let mut file = fixture_file(&batch, &bytes);
    file.name = batch_file_name(43, &batch.source_device_id, &batch.batch_id);
    assert!(!matches_batch_metadata(&file, &batch));
    for generation in ["-1", "+42", "", "NaN", "9007199254740992"] {
        file.app_properties
            .insert(APP_PROPERTY_GENERATION.into(), generation.into());
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
        (
            "/events/0/replay_count",
            serde_json::json!(i32::MAX as i64 + 1),
        ),
        ("/events/0/volume_level", serde_json::json!(101)),
        ("/events/0/title", serde_json::json!(" ")),
        ("/events/0/album", serde_json::json!("x".repeat(1001))),
    ];
    for (pointer, value) in mutations {
        let mut malformed = original.clone();
        *malformed.pointer_mut(pointer).unwrap() = value;
        assert!(
            decode_batch(&compress_json(&malformed)).is_err(),
            "{pointer}"
        );
    }
    for field in ["album", "session_id", "site", "volume_level"] {
        let mut malformed = original.clone();
        malformed["events"][0]
            .as_object_mut()
            .unwrap()
            .remove(field);
        assert!(
            decode_batch(&compress_json(&malformed)).is_err(),
            "missing {field}"
        );
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
         VALUES ('Old title', 'Artist', 1700000000000)",
        [],
    )
    .unwrap();
    let scrobble_id = conn.last_insert_rowid();
    let producer = load_state(&conn).unwrap().device_id;
    drop(conn);

    let (_, first) = persist_lan_origin_metadata(
        &directory,
        &[(
            scrobble_id,
            1_700_000_000_000,
            "Old title".into(),
            "Artist".into(),
        )],
    )
    .unwrap();
    let origin = first[&scrobble_id].clone();
    let conn = open_sync_db(&directory).unwrap();
    let uploaded: Option<i64> = conn
        .query_row(
            "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = ?1",
            [scrobble_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(
        uploaded, None,
        "sending via LAN must not mark a Drive upload"
    );
    conn.execute(
        "UPDATE scrobbles SET title = 'Corrected title' WHERE id = ?1",
        [scrobble_id],
    )
    .unwrap();
    drop(conn);

    let (again_device, later) = persist_lan_origin_metadata(
        &directory,
        &[(
            scrobble_id,
            1_700_000_000_000,
            "Corrected title".into(),
            "Artist".into(),
        )],
    )
    .unwrap();
    assert_eq!(producer, again_device);
    assert_eq!(
        later[&scrobble_id], origin,
        "LAN retries must keep the first ID"
    );
    let conn = open_sync_db(&directory).unwrap();
    let pending = pending_local_plays(&conn).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(
        event_id(&producer, &pending[0]),
        origin,
        "subsequent Drive uploads must reuse the LAN-first origin ID"
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn pinned_drive_origin_survives_a_crash_before_server_acknowledgment() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Track title', 'Performer', 1700000000000)",
        [],
    )
    .unwrap();
    let id = conn.last_insert_rowid();
    let producer = load_state(&conn).unwrap().device_id;
    let original = pin_local_origin(
        &conn,
        &producer,
        id,
        1_700_000_000_000,
        "Track title",
        "Performer",
    )
    .unwrap();
    drop(conn);
    let reopened = open_sync_db(&directory).unwrap();
    let later = pin_local_origin(
        &reopened,
        &producer,
        id,
        1_700_000_000_000,
        "Renamed",
        "Performer",
    )
    .unwrap();
    assert_eq!(original, later);
    assert_eq!(
        pending_local_plays(&reopened).unwrap().len(),
        1,
        "pinning the ID must not mark a pending Drive upload as completed"
    );
    drop(reopened);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn previously_uploaded_origin_is_stable_after_track_metadata_changes() {
    let (directory, conn) = history_storage_fixture();
    let id = conn
        .execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES ('Original', 'Artist', 1700000000000)",
            [],
        )
        .unwrap();
    assert_eq!(id, 1);
    let scrobble_id = conn.last_insert_rowid();
    let first_identity = lan_play_origin(
        "test-device",
        scrobble_id,
        1_700_000_000_000,
        "Original",
        "Artist",
    );
    conn.execute(
        "INSERT INTO drive_event_state
         (scrobble_id, origin_event_id, origin_device_id, drive_imported, drive_uploaded_at)
         VALUES (?1, ?2, 'test-device', 0, NULL)",
        params![scrobble_id, first_identity],
    )
    .unwrap();
    conn.execute(
        "UPDATE scrobbles SET title = 'Corrected title' WHERE id = ?1",
        [scrobble_id],
    )
    .unwrap();
    let (_, known) = lan_origin_metadata(&directory, &[scrobble_id]).unwrap();
    assert_eq!(
        known.get(&scrobble_id),
        Some(&first_identity),
        "LAN must reuse the original Drive identity after metadata correction"
    );
    let pending = pending_local_plays(&conn).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(
        event_id("test-device", &pending[0]),
        first_identity,
        "a metadata correction must not change a previously uploaded event ID"
    );
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
    file.app_properties
        .insert(APP_PROPERTY_GENERATION.into(), "10".into());
    assert!(should_delete_history_batch(&file, 20, false));
    file.app_properties
        .insert(APP_PROPERTY_GENERATION.into(), "20".into());
    assert!(!should_delete_history_batch(&file, 20, true));
    file.app_properties
        .insert(APP_PROPERTY_GENERATION.into(), "21".into());
    assert!(!should_delete_history_batch(&file, 20, true));
    file.app_properties
        .insert(APP_PROPERTY_GENERATION.into(), "invalid".into());
    assert!(!should_delete_history_batch(&file, 20, false));
    assert!(should_delete_history_batch(&file, 20, true));
    // Missing/corrupted properties must not erase a newly published generation
    // whose immutable filename still identifies it as current or newer.
    file.name = batch_file_name(20, "device-1", &"a".repeat(64));
    file.app_properties.remove(APP_PROPERTY_GENERATION);
    assert!(!should_delete_history_batch(&file, 20, false));
    assert!(!should_delete_history_batch(&file, 20, true));
    file.name = batch_file_name(21, "device-1", &"a".repeat(64));
    file.app_properties
        .insert(APP_PROPERTY_GENERATION.into(), "invalid".into());
    assert!(!should_delete_history_batch(&file, 20, false));
    assert!(!should_delete_history_batch(&file, 20, true));
    // Older malformed files can still be removed by deliberate deletion.
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
        .await
        .unwrap();
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
    for (volume, expected) in [
        (0.0, Some(0)),
        (0.001, Some(1)),
        (0.01, Some(1)),
        (0.5, Some(50)),
        (1.0, Some(100)),
        (1.5, Some(100)),
        (-1.0, None),
        (f64::NAN, None),
    ] {
        play.volume_level = volume;
        assert_eq!(protocol_volume(&play), expected, "volume={volume}");
    }
    play.is_muted = true;
    assert_eq!(protocol_volume(&play), Some(0));
}


#[test]
fn desktop_upload_scan_resumes_after_invalid_prefix_and_preserves_account_scope() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1, account_subject = 'google-a',
         last_verified_account_subject = 'google-a' WHERE id = 1",
        [],
    ).unwrap();
    let tx = conn.unchecked_transaction().unwrap();
    for i in 0..5_005_i64 {
        tx.execute(
            "INSERT INTO scrobbles (title, artist, timestamp_utc)
             VALUES ('', 'Artist', ?1)",
            [1_700_000_000_000_i64 + i],
        ).unwrap();
    }
    tx.commit().unwrap();

    let start = pending_local_plays_page(&conn, None, MAX_LOCAL_SCAN).unwrap();
    assert_eq!(start.len(), MAX_LOCAL_SCAN);
    assert!(!valid_event(&local_to_wire(&load_state(&conn).unwrap().device_id, &start[0])));
    mark_invalid_local_export(&conn, "google-a", start[0].id).unwrap();
    assert_eq!(invalid_local_export_summary(&conn, "google-a").unwrap(), (1, Some(start[0].id)));

    let last = start.last().unwrap();
    save_upload_scan_cursor(&conn, "google-a", Some((last.timestamp_utc, last.id))).unwrap();
    assert_eq!(load_upload_scan_cursor(&conn, "google-a").unwrap(),
        Some((last.timestamp_utc, last.id)));
    assert_eq!(load_upload_scan_cursor(&conn, "google-b").unwrap(), None);
    assert_eq!(invalid_local_export_summary(&conn, "google-b").unwrap(), (0, None));

    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let cursor = load_upload_scan_cursor(&conn, "google-a").unwrap();
    let remaining = pending_local_plays_page(&conn, cursor, MAX_LOCAL_SCAN).unwrap();
    assert_eq!(remaining.len(), 5, "valid events after 5k malformed ones stay reachable");
    assert_eq!(invalid_local_export_summary(&conn, "google-a").unwrap().0, 1);
    clear_invalid_local_export(&conn, "google-a", start[0].id).unwrap();
    assert_eq!(invalid_local_export_summary(&conn, "google-a").unwrap(), (0, None));

    mark_invalid_local_export(&conn, "google-a", start[0].id).unwrap();
    conn.execute("DELETE FROM scrobbles WHERE id = ?1", [start[0].id]).unwrap();
    assert_eq!(invalid_local_export_summary(&conn, "google-a").unwrap(), (0, None));
    save_upload_scan_cursor(&conn, "google-a", None).unwrap();
    assert_eq!(load_upload_scan_cursor(&conn, "google-a").unwrap(), None);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}


#[test]
fn reconnect_never_downgrades_a_previously_accepted_cloud_deletion_marker() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 0,
         account_subject = 'google-a', accepted_disable_version = 300,
         download_cursor = 123, last_error = 'disconnected'
         WHERE id = 1",
        [],
    )
    .unwrap();

    // A stale or duplicate remote marker cannot suppress any additional
    // local captures or downgrade a previously confirmed deletion generation.
    assert!(accept_deletion_marker(&conn, 250, None).is_err());
    assert!(accept_deletion_marker(&conn, 300, None).is_err());
    assert!(!load_state(&conn).unwrap().enabled);
    assert_eq!(load_state(&conn).unwrap().accepted_disable_version, 300);

    // A temporarily missing Drive control file must not restore generation 0.
    finish_drive_reconnect(&conn, 0).unwrap();
    let state = load_state(&conn).unwrap();
    assert!(state.enabled);
    assert_eq!(state.accepted_disable_version, 300);
    assert_eq!(state.download_cursor, 123);
    assert_eq!(state.last_error, None);

    // An out-of-date remote listing also cannot roll the generation backward.
    finish_drive_reconnect(&conn, 250).unwrap();
    let state = load_state(&conn).unwrap();
    assert_eq!((state.accepted_disable_version, state.download_cursor), (300, 123));

    // A genuinely newer remote marker is adopted and invalidates the cursor.
    finish_drive_reconnect(&conn, 400).unwrap();
    let state = load_state(&conn).unwrap();
    assert_eq!((state.accepted_disable_version, state.download_cursor), (400, 0));
    assert!(finish_drive_reconnect(&conn, -1).is_err());

    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}
