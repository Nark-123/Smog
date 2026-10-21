package smog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Cumulative session counters, owner thread only. Millisecond fields are already in ms. */
public final class RestartMetrics {

    private final LinkedHashMap<String, Double> values = new LinkedHashMap<>();

    public RestartMetrics() {
        for (String name : ("checkpoint_read_ms checkpoint_decode_ms checkpoint_bytes checkpoint_cells checkpoint_format_version "
            + "cache_entries cache_hits cache_misses cache_invalid_world cache_invalid_config cache_corrupt cache_read_ms "
            + "cache_decompress_ms cache_fingerprint_ms cache_bytes_read cache_memory_bytes cache_write_failures cache_write_dropped cache_shutdown_timeout cache_reactivation_cells "
            + "cache_write_regions cache_write_cells cache_write_updates cache_write_bytes cache_rewrite_read_bytes cache_write_ms cache_write_max_ms "
            + "restart_cells_total restart_cells_ready restart_cells_published restart_cells_waiting_chunk restart_cells_unchecked "
            + "fast_restore_cells fallback_restore_cells fast_restore_batches fast_restore_nodes fast_restore_edges "
            + "fast_restore_commit_ms fast_restore_max_commit_ms dimension_to_first_ready_ms "
            + "startup_registered_columns startup_relevant_columns column_event_ms resume_ready_ms "
            + "world_snapshot_build_ms memory_mass_records_bytes memory_snapshot_bytes memory_published_bytes "
            + "memory_graph_bytes cache_inflight_bytes validation_ms validation_checks validation_backlog "
            + "validation_waiting validation_oldest_ticks validation_validated validation_dirty validation_stale "
            + "fast_restore_reserve_ms fast_restore_max_reserve_ms cache_decode_ms fast_restore_nodes_ms "
            + "fast_restore_edges_ms fast_restore_front_ms fast_restore_events_ms fast_restore_finish_ms "
            + "graph_live_nodes graph_node_slots graph_live_edges graph_edge_slots solver_pending_frames "
            + "solver_oldest_frame_ticks server_geometry_budget_ms server_previous_mspt").split(" "))
            values.put(name, 0.0);
        for (String name : ("epoch_append_cells epoch_append_nodes epoch_append_edges epoch_append_batches "
            + "epoch_append_ms epoch_append_max_ms epoch_append_reserve_ms epoch_append_reserve_max_ms "
            + "epoch_prepare_ms epoch_contacts_ms epoch_wait_ms epoch_fallback_cells epoch_pending_solver_activation "
            + "restore_saved_ready restore_mass_ratio restore_mass_50_ms restore_mass_90_ms restore_mass_99_ms "
            + "restore_cells_1s restore_cells_5s restore_cells_10s restore_cells_30s restore_cells_60s "
            + "server_mspt_p95 server_mspt_p99 server_mspt_max").split(" ")) values.put(name, 0.0);
        for (String name : ("capacity_retries preflight_rechecks neighbor_lookups_per_append "
            + "plan_stop_cell_limit plan_stop_grouping_disabled plan_stop_region_boundary plan_stop_reactivation plan_stop_edge_limit plan_stop_node_limit "
            + "plan_stop_not_ready plan_stop_cache_not_ready batch_stop_complete batch_stop_cell_limit "
            + "batch_stop_node_limit batch_stop_edge_limit batch_stop_stale").split(" ")) values.put(name, 0.0);
        for (String histogram : new String[] { "planCells", "planCubes", "batchCells", "batchNodes", "batchEdges" })
            for (String bucket : new String[] { "0", "1", "2_4", "5_8", "9_16", "17_32", "33_64", "65_128", "129_256",
                "257_512", "over_512" }) values.put(histogram + "_" + bucket, 0.0);
        for (String name : ("fallback_prepared_ready fallback_solver_blocked fallback_epoch_eligible "
            + "fallback_epoch_appended_cells fallback_safe_cut_cells fallback_replacement_cells fallback_invalidated "
            + "fallback_commit_ms fallback_commit_max_ms fallback_prepare_ms fallback_mass_finalization_ms "
            + "fallback_solver_wait_ms prepared_append_queue prepared_replacement_queue build_replacement_queue "
            + "ready_but_blocked prepared_backpressure_calls append_head_age_ticks replacement_head_age_ticks "
            + "build_head_age_ticks append_head_age_ms replacement_head_age_ms geometry_grant_ms geometry_used_ms "
            + "runtime_wall_ms runtime_server_ticks build_ticket_restarts").split(" ")) values.put(name, 0.0);
        for (String name : ("unique_dependency_checks reused_dependency_checks validation_stale_rechecks "
            + "cache_read_failures cache_read_wait_ms cache_read_wait_max_ms cache_read_requests cache_write_coalesced "
            + "cache_pending_regions cache_pending_bytes cache_rewrite_read_ms cache_encode_write_ms "
            + "cache_atomic_replace_ms cache_region_reuse cache_write_retries cache_flush_failed "
            + "cache_pending_oldest_ms").split(" ")) values.put(name, 0.0);
        set("restore_mass_50_ms", -1);
        set("restore_mass_90_ms", -1);
        set("restore_mass_99_ms", -1);
        set("dimension_to_first_ready_ms", -1);
    }

    public double get(String key) {
        return values.get(key);
    }

    public void set(String key, double value) {
        if (!values.containsKey(key)) throw new IllegalArgumentException(key);
        values.put(key, value);
    }

    void add(String key, double amount) {
        set(key, get(key) + amount);
    }

    void max(String key, double amount) {
        set(key, Math.max(get(key), amount));
    }

    void histogram(String name, int count) {
        String bucket = count == 0 ? "0"
            : count == 1 ? "1"
                : count <= 4 ? "2_4"
                    : count <= 8 ? "5_8"
                        : count <= 16 ? "9_16"
                            : count <= 32 ? "17_32"
                                : count <= 64 ? "33_64"
                                    : count <= 128 ? "65_128"
                                        : count <= 256 ? "129_256" : count <= 512 ? "257_512" : "over_512";
        add(name + "_" + bucket, 1);
    }

    public String csvHeader() {
        return String.join(",", values.keySet());
    }

    public String csvValues() {
        StringBuilder s = new StringBuilder();
        for (double v : values.values()) {
            if (s.length() != 0) s.append(',');
            s.append(String.format(Locale.ROOT, "%.6f", v));
        }
        return s.toString();
    }

    public String logLine() {
        StringBuilder s = new StringBuilder("restartTotals");
        for (Map.Entry<String, Double> e : values.entrySet()) s.append(' ')
            .append(e.getKey())
            .append('=')
            .append(String.format(Locale.ROOT, "%.3f", e.getValue()));
        return s.toString();
    }
}
