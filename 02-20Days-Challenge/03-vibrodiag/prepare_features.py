#!/usr/bin/env python3
"""Prepare the four local VibroDiag feature tables; no training or network access.

Run with Python 3.14 and NumPy; optionally pass a different read-only location
for the same export. Output is always the inputs/ directory beside this script.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from importlib.util import module_from_spec, spec_from_file_location
import json
import math
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
import wave

sys.dont_write_bytecode = True
import numpy as np

HERE = Path(__file__).resolve().parent
FEATURES_PATH = HERE / "features.py"
if not FEATURES_PATH.is_file():
    FEATURES_PATH = HERE.parent / "phone-lab" / "analysis" / "vibro" / "features.py"
DEFAULT_SOURCE = HERE / "raw_data"
SESSION = "VD-20261122-002"
CONDITIONS = (
    "C1_NORMAL_LOW", "C2_NORMAL_HIGH", "C3_IMBALANCE", "C4_BLOCKED", "C5_HAIRDRYER",
)
GROUPS = range(1, 16)
DURATION_S = 15
SNR_LEVELS = (20, 10, 0)
SEED = 20261122002
PCM_MAX = 32767 / 32768
ACCEL_HEADER = ["t_ns", "t_ms", "x", "y", "z", "mag_g", "sample_rate_hz"]
INDEX_HEADER = [
    "session_id", "condition_id", "condition_label", "groups_collected", "target_groups", "complete",
]
EXPORTED_LOG_ZONE = timezone(timedelta(hours=8), "Asia/Shanghai")  # Verified from this 2026 manifest.


def require(ok, context, detail):
    if not ok:
        raise ValueError(f"{context}: {detail}")


def load_feature_module():
    spec = spec_from_file_location("vibro_schema_features", FEATURES_PATH)
    require(spec is not None and spec.loader is not None, "features.py", "cannot load feature module")
    module = module_from_spec(spec)
    # Hash exactly the source bytes executed; do not use caches or re-read a mutable module later.
    source = FEATURES_PATH.read_bytes()
    exec(compile(source, str(FEATURES_PATH), "exec", dont_inherit=True), module.__dict__)
    return module, sha256(source).hexdigest()


def require_plain(path, is_dir=False):
    require(not path.is_symlink() and not path.is_junction(), path.name, "links are not allowed")
    require(path.is_dir() if is_dir else path.is_file(), path.name, "missing or wrong path type")


def read_json(path):
    require_plain(path)
    with path.open(encoding="utf-8-sig") as handle:
        value = json.load(handle)
    require(isinstance(value, dict), path.name, "expected a JSON object")
    return value


def csv_rows(path, expected_header):
    require_plain(path)
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle, strict=True)
        require(next(reader, None) == expected_header, path.name, "CSV header mismatch")
        for row in reader:
            require(len(row) == len(expected_header), path.name, f"malformed CSV line {reader.line_num}")
            yield dict(zip(expected_header, row, strict=True))


def manifest_and_index(source, sample_rate):
    manifest = read_json(source / "manifest.json")
    require(manifest["schema"] == "phonelab.manifest/1" and manifest["sessionId"] == SESSION,
            "manifest", "schema or session mismatch")
    ids = tuple(item["id"] for item in manifest["conditions"])
    require(ids == CONDITIONS and manifest["targetGroups"] == len(GROUPS), "manifest", "not the expected 5×15 cohort")
    labels = {item["id"]: item["label"] for item in manifest["conditions"]}
    require(all(isinstance(label, str) and label for label in labels.values()), "manifest", "missing labels")
    audio = manifest["audio"]
    require((audio["appSampleRate"], audio["channels"], audio["bits"]) == (sample_rate, 1, 16),
            "manifest", "unexpected App audio format")
    require(manifest["device"]["timeZone"] == "Asia/Shanghai", "manifest", "unexpected log time zone")
    index = list(csv_rows(source / "dataset_index.csv", INDEX_HEADER))
    require(len(index) == len(CONDITIONS), "dataset_index", "wrong row count")
    require({row["condition_id"] for row in index} == set(CONDITIONS), "dataset_index", "missing or repeated condition")
    for row in index:
        require((row["session_id"], row["condition_label"], row["groups_collected"],
                 row["target_groups"], row["complete"]) == (SESSION, labels[row["condition_id"]], "15", "15", "1"),
                "dataset_index", f"invalid row for {row['condition_id']}")
    return labels, audio["resolvedSource"]


def check_group(group_dir, condition, index, label, audio_source, F):
    stem = f"g{index:02d}_{condition}"
    paths = {key: group_dir / f"{stem}_{suffix}" for key, suffix in {
        "wav": "audio.wav", "accel": "accel.csv", "feature": "features.csv", "meta": "meta.json",
    }.items()}
    require({p.name for p in group_dir.iterdir()} == {p.name for p in paths.values()},
            stem, "expected exactly the four named group files")
    for p in paths.values():
        require_plain(p)
    meta = read_json(paths["meta"])
    require((meta["schema"], meta["session_id"], meta["condition_id"], meta["condition_label"],
             meta["group_index"]) == ("phonelab.vibro/1", SESSION, condition, label, index)
            and type(meta["group_index"]) is int, stem, "metadata labels do not match folder and manifest")
    require(all(meta[k] is False for k in ("interrupted", "blind_trial", "exclude_from_dataset")),
            stem, "interrupted, blind or excluded capture")
    recorded_at = meta["recorded_at_ms"]
    require(type(recorded_at) is int and recorded_at > 0, stem, "invalid recorded_at_ms")
    require(meta["duration_s"] == DURATION_S, stem, "unexpected recording duration in metadata")
    a, b = meta["audio"], meta["accel"]
    require((a["file"], a["source"], b["file"]) == (paths["wav"].name, audio_source, paths["accel"].name),
            stem, "WAV/accelerometer metadata filename or audio source mismatch")
    archived = list(csv_rows(paths["feature"], F.CSV_HEADER))
    require(len(archived) == 1, stem, "archived feature CSV must have exactly one row")
    row = archived[0]
    require((row["condition_id"], row["condition_label"], int(row["group_index"]), row["file_stem"],
             int(row["recorded_at_ms"]), float(row["duration_s"])) == (
                 condition, label, index, stem, recorded_at, DURATION_S),
            stem, "archived feature labels do not match metadata/folder")
    require(all(not row[name] or math.isfinite(float(row[name])) for name in F.NUMERIC_COLUMNS),
            stem, "nonblank archived feature values must be finite")
    return stem, paths, meta, recorded_at, row


def read_checked_audio(path, meta, F):
    with path.open("rb") as handle:
        file_hash = sha256(handle.read()).hexdigest()
    with wave.open(str(path), "rb") as wav:
        nch, width, hz, n = wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getnframes()
        require((nch, width, hz, wav.getcomptype(), n) == (
            1, 2, F.AUDIO_SAMPLE_RATE, "NONE", F.AUDIO_SAMPLE_RATE * DURATION_S),
            path.name, "invalid WAV format or duration")
        require((meta["channels"], meta["bits"], meta["sample_rate"]) == (nch, 8 * width, hz),
                path.name, "WAV header disagrees with metadata")
        pcm = wav.readframes(n)
    require(len(pcm) == n * width, path.name, "truncated WAV payload")
    x, sr = F.read_wav(path)
    require(sr == hz and x.shape == (n,) and np.any(x != 0), path.name, "empty, silent or malformed audio")
    rails = int(np.count_nonzero((x == -1.0) | (x == PCM_MAX)))
    return x, sr, file_hash, sha256(pcm).hexdigest(), rails


def read_checked_accel(path, meta, F):
    rows = list(csv_rows(path, ACCEL_HEADER))
    require(len(rows) >= 8 and type(meta["samples"]) is int and len(rows) == meta["samples"],
            path.name, "accelerometer count disagrees with metadata or is insufficient")
    ts = np.array([int(row["t_ns"]) for row in rows], dtype=np.int64)
    values = np.array([[float(row[name]) for name in ACCEL_HEADER[1:]] for row in rows])
    t_ms, xyz, mag, rate = values[:, 0], values[:, 1:4], values[:, 4], values[:, 5]
    require(np.isfinite(values).all() and ts[0] > 0 and (np.diff(ts) > 0).all() and
            t_ms[0] >= 0 and (np.diff(t_ms) > 0).all(), path.name, "invalid or nonmonotonic accelerometer samples")
    require(t_ms[-1] <= DURATION_S * 1000, path.name, "accelerometer extends past same-group WAV duration")
    require(np.max(np.abs((t_ms - t_ms[0]) - (ts - ts[0]) / 1e6)) <= 0.00011,
            path.name, "accelerometer timestamp columns disagree beyond CSV rounding")
    require(np.max(np.abs(mag - np.linalg.norm(xyz, axis=1) / 9.80665)) <= 0.00006,
            path.name, "acceleration magnitude disagrees with XYZ beyond CSV rounding")
    require(rate[0] > 0 and (rate == rate[0]).all() and abs(rate[0] - float(meta["sample_rate_hz"])) <= 0.000051,
            path.name, "invalid/inconsistent accelerometer rates or rate disagrees with metadata")
    measured_hz = (len(rows) - 1) * 1e9 / int(ts[-1] - ts[0])
    require(abs(measured_hz - rate[0]) <= 0.01, path.name, "reported accelerometer rate disagrees with timestamps")
    accel = F.read_accel_csv(path)
    require(accel["sample_rate_hz"] == rate[0] and
            np.array_equal(np.column_stack([accel[axis] for axis in "xyz"]), xyz),
            path.name, "feature module did not read all original XYZ samples")
    signal_hash = sha256(np.asarray(xyz, dtype="<f8", order="C").tobytes()).hexdigest()
    return accel, signal_hash, int(ts[0]), int(ts[-1]), len(rows)


def raw_unique(seen, digest, stem, kind):
    require(digest not in seen, stem, f"duplicate {kind} from {seen.get(digest)}")
    seen[digest] = stem


def rms(x):
    return math.sqrt(float(np.mean(x * x)))


def make_noisy(x, x_rms, normal, target_db):
    noise = normal * (x_rms * 10.0 ** (-target_db / 20.0))
    mixture = x + noise
    would_clip = int(np.count_nonzero((mixture < -1.0) | (mixture > PCM_MAX)))
    # Apply the same gain to both components, not a nonlinear clip to their sum.
    minimum, maximum = float(mixture.min()), float(mixture.max())
    gain = min(1.0 / -minimum if minimum < -1.0 else 1.0,
               PCM_MAX / maximum if maximum > PCM_MAX else 1.0)
    if would_clip:
        gain = float(np.nextafter(gain, 0.0))
        mixture *= gain
    out_of_range = int(np.count_nonzero((mixture < -1.0) | (mixture > PCM_MAX)))
    actual_snr = 20.0 * math.log10(gain * x_rms / rms(mixture - gain * x))
    require(out_of_range == 0 and abs(actual_snr - target_db) < 1e-9,
            "noise", "post-gain mix out of range or SNR mismatch")
    return mixture, {
        "actual_snr_db": actual_snr, "pre_gain_out_of_pcm16_range_samples": would_clip,
        "linear_gain": gain, "post_gain_out_of_pcm16_range_samples": out_of_range,
    }


def iso_ms(ms, zone=timezone.utc):
    return datetime.fromtimestamp(ms / 1000, zone).isoformat(timespec="milliseconds")


def recording_log(path, ordered_groups):
    require_plain(path)
    start = None
    recent_finish = None
    saved = []
    starts = 0
    deletes = 0
    save_re = re.compile(r"I/SessionRepository: saved (g\d{2}_\w+) interrupted=(false|true) audio=([\d.]+)s$")
    finish_re = re.compile(r"I/RecordingEngine: recording finished duration=([\d.]+)s interrupted=(false|true) accelSamples=(\d+) error=(.*)$")
    with path.open(encoding="utf-8-sig") as log:
        for line_no, line in enumerate(log, 1):
            text = line[24:].strip()
            if text.startswith("I/SessionRepository: deleted sample "):
                deletes += 1
            event = save_re.fullmatch(text)
            if not (event or text.startswith("I/RecordingEngine: recording started ") or finish_re.fullmatch(text)):
                continue
            dt = datetime.fromisoformat(line[:23]).replace(tzinfo=EXPORTED_LOG_ZONE)
            epoch_ms = round(dt.timestamp() * 1000)
            if event:
                saved.append((line_no, start, recent_finish, epoch_ms, *event.groups()))
                start = recent_finish = None
            elif text.startswith("I/RecordingEngine: recording started "):
                start = epoch_ms
                recent_finish = None
                starts += 1
            else:
                recent_finish = finish_re.fullmatch(text).groups()
    matched_lines = []
    deltas = []
    mismatches = []
    for group in ordered_groups:
        stem, at = group["file_stem"], group["recorded_at_ms"]
        candidates = [event for event in saved if event[4] == stem and event[1] is not None and
                      abs(event[1] - at) <= 1000 and event[5] == "false"]
        if len(candidates) != 1:
            mismatches.append({"file_stem": stem, "issue": "not exactly one successful save within 1 s of metadata start"})
            continue
        line_no, log_start, finish, log_save, _, _, duration = candidates[0]
        matched_lines.append(line_no)
        deltas.append(log_start - at)
        if (float(duration) != DURATION_S or not finish or
                (float(finish[0]), finish[1], int(finish[2]), finish[3]) !=
                (DURATION_S, "false", group["accel_samples"], "null")):
            mismatches.append({"file_stem": stem, "issue": "session.log finish/save status differs from final group"})
        group["matched_log_start_utc"] = iso_ms(log_start)
        group["matched_log_save_utc"] = iso_ms(log_save)
    return {
        "local_time_zone": "Asia/Shanghai (+08:00, from export manifest)",
        "recording_start_entries": starts, "save_entries": len(saved),
        "interrupted_save_entries": sum(event[5] == "true" for event in saved),
        "explicit_delete_entries": deletes,
        "matched_final_groups": len(matched_lines),
        "save_entries_not_matched_to_a_final_group": len(saved) - len(matched_lines),
        "matched_saves_in_metadata_order": len(matched_lines) == len(ordered_groups) and matched_lines == sorted(matched_lines),
        "log_minus_meta_start_ms_min_max": [min(deltas), max(deltas)] if deltas else None,
        "mismatches": mismatches,
    }


def validate_outputs(tables, groups, F):
    expected = [(condition, index) for condition in CONDITIONS for index in GROUPS]
    header = ["condition_id", "group_index", *F.NUMERIC_COLUMNS]
    require(len(header) == len(set(header)) == 34 and len(groups) == len(expected), "output", "schema or group-count mismatch")
    accel_columns = F.ACCEL_TIME + F.ACCEL_SPECTRUM + F.ACCEL_BANDS
    accel_ins = [header.index(name) for name in accel_columns]
    for name, rows in tables.items():
        require([tuple(row[:2]) for row in rows] == expected and all(len(row) == len(header) for row in rows),
                name, "group-order/output-width mismatch")
        require(all(not cell or math.isfinite(float(cell)) for row in rows for cell in row[2:]), name, "non-finite field")
        require(all(row[i] == ref[i] for row, ref in zip(rows, tables["clean"], strict=True) for i in accel_ins),
                name, "accelerometer features changed in noisy data")
    return header


def prepare(source, output):
    require_plain(source, is_dir=True)
    source = source.resolve(strict=True)
    require(not output.resolve().is_relative_to(source) and not source.is_relative_to(output.resolve()),
            "inputs", "source and output directories must be disjoint")
    F, feature_module_hash = load_feature_module()
    labels, audio_source = manifest_and_index(source, F.AUDIO_SAMPLE_RATE)
    tables = {name: [] for name in ("clean", *(f"white_{db}db" for db in SNR_LEVELS))}
    seen_wav, seen_pcm, seen_xyz = {}, {}, {}
    groups = []
    archived_diff = Counter()
    invalid_accel_band = Counter()
    partial_accel_band_masked = Counter()
    metadata_summary_diffs = []
    for ordinal, condition in enumerate(CONDITIONS, 1):
        condition_dir = source / condition
        require_plain(condition_dir, is_dir=True)
        require({p.name for p in condition_dir.iterdir()} == {f"g{i:02d}" for i in GROUPS},
                condition, "expected exactly groups g01..g15")
        for index in GROUPS:
            group_dir = condition_dir / f"g{index:02d}"
            require_plain(group_dir, is_dir=True)
            stem, paths, meta, at, archived = check_group(group_dir, condition, index, labels[condition], audio_source, F)
            x, sr, file_hash, pcm_hash, rails = read_checked_audio(paths["wav"], meta["audio"], F)
            accel, xyz_hash, first_ns, last_ns, n_accel = read_checked_accel(paths["accel"], meta["accel"], F)
            raw_unique(seen_wav, file_hash, stem, "entire WAV")
            raw_unique(seen_pcm, pcm_hash, stem, "PCM payload")
            raw_unique(seen_xyz, xyz_hash, stem, "accelerometer XYZ sequence")
            vibration = F.extract_accel_features(accel["x"], accel["y"], accel["z"], accel["sample_rate_hz"])
            nyquist = accel["sample_rate_hz"] / 2.0
            # The App and feature_spec §4.6 require the full band to be observable.
            # The shared Python function only checks the lower edge and may yield a partial-band value.
            for name, (low, high) in zip(F.ACCEL_BANDS, F.ACCEL_BAND_EDGES, strict=True):
                if low >= nyquist or high > nyquist:
                    invalid_accel_band[name] += 1
                    if math.isfinite(vibration[name]):
                        partial_accel_band_masked[name] += 1
                    vibration[name] = float("nan")
            clean_audio = F.extract_audio_features(x, sr)
            merged = {**clean_audio, **vibration}
            require(merged.keys() == set(F.NUMERIC_COLUMNS), stem, "extractor output schema mismatch")
            require(not any(math.isinf(v) for v in merged.values()), stem, "extractor returned infinity")
            clean_fmt = F.feature_row(merged)
            tables["clean"].append([condition, index, *(clean_fmt[k] for k in F.NUMERIC_COLUMNS)])
            # Diagnostics only. App acceleration used samples before export rounding to 4 decimals.
            for name in F.NUMERIC_COLUMNS:
                actual, prior = clean_fmt[name], archived[name]
                if (bool(actual) != bool(prior)) or (actual and prior and abs(float(actual) - float(prior)) > 2e-6):
                    archived_diff[name] += 1
            for field, archive_value in (("audio_rms_dbfs", meta["audio"]["rms_dbfs"]),
                                         ("audio_peak_dbfs", meta["audio"]["peak_dbfs"])):
                if not math.isclose(clean_audio[field], float(archive_value), rel_tol=0, abs_tol=2e-6):
                    metadata_summary_diffs.append({"file_stem": stem, "field": field})
            group = {
                "file_stem": stem, "condition_id": condition, "group_index": index,
                "recorded_at_ms": at, "recorded_start_utc": iso_ms(at),
                "wav_samples": x.size, "source_pcm16_rail_samples": rails,
                "accel_samples": n_accel, "accel_sample_rate_hz": accel["sample_rate_hz"],
                "accel_nyquist_hz": nyquist,
                "accel_first_t_ns": first_ns, "accel_last_t_ns": last_ns, "white_noise": {},
            }
            rng = np.random.Generator(np.random.PCG64(np.random.SeedSequence([SEED, ordinal, index])))
            white = rng.standard_normal(x.size, dtype=np.float64)
            white -= white.mean()
            white /= rms(white)
            x_rms = rms(x)
            for target in SNR_LEVELS:
                mix, stats = make_noisy(x, x_rms, white, target)
                group["white_noise"][str(target)] = stats
                audio_features = F.extract_audio_features(mix, sr)
                merged = {**audio_features, **vibration}
                require(merged.keys() == set(F.NUMERIC_COLUMNS) and not any(math.isinf(v) for v in merged.values()),
                        stem, "noisy feature schema mismatch or infinity")
                fmt = F.feature_row(merged)
                tables[f"white_{target}db"].append([condition, index, *(fmt[k] for k in F.NUMERIC_COLUMNS)])
            groups.append(group)
        print(f"Validated and extracted {condition}: {len(GROUPS)} groups", flush=True)

    header = validate_outputs(tables, groups, F)
    ordered = sorted(groups, key=lambda g: g["recorded_at_ms"])
    times = [g["recorded_at_ms"] for g in ordered]
    accel_ordered = sorted(groups, key=lambda g: g["accel_first_t_ns"])
    gaps = [b - a for a, b in zip(times, times[1:])]
    duration_ms = DURATION_S * 1000
    ordered_conditions = [g["condition_id"] for i, g in enumerate(ordered)
                          if i == 0 or g["condition_id"] != ordered[i-1]["condition_id"]]
    chronology = {
        "first_recorded_start_utc": iso_ms(times[0]), "last_recorded_start_utc": iso_ms(times[-1]),
        "last_recorded_end_utc_from_wav_duration": iso_ms(times[-1] + duration_ms),
        "first_recorded_start_export_local": iso_ms(times[0], EXPORTED_LOG_ZONE),
        "last_recorded_end_export_local_from_wav_duration": iso_ms(times[-1] + duration_ms, EXPORTED_LOG_ZONE),
        "chronological_condition_blocks": ordered_conditions,
        "chronological_group_stems": [g["file_stem"] for g in ordered],
        "unique_recorded_starts": len(set(times)), "metadata_order_matches_csv_order": ordered == groups,
        "accel_clock_order_matches_metadata_order": accel_ordered == ordered,
        "min_max_start_gap_seconds": [min(gaps) / 1000, max(gaps) / 1000],
        "overlapping_wav_recording_intervals_by_metadata": [
            [a["file_stem"], b["file_stem"]] for a, b in zip(ordered, ordered[1:])
            if b["recorded_at_ms"] - a["recorded_at_ms"] < duration_ms
        ],
        "overlapping_accel_intervals_by_sensor_clock": [
            [a["file_stem"], b["file_stem"]] for a, b in zip(accel_ordered, accel_ordered[1:])
            if b["accel_first_t_ns"] <= a["accel_last_t_ns"]
        ],
    }
    log_check = recording_log(source / "session.log", ordered)
    provenance = {
        "session_id": SESSION, "group_count": len(groups), "label_source": "Directory, manifest, dataset_index.csv, feature CSV and metadata agree; this does not independently verify the physical experiment or labels.",
        "output_order": "C1 through C5; within each condition g01 through g15, not reordered using capture times",
        "csv_header": header, "python_version": sys.version.split()[0], "numpy_version": np.__version__,
        "feature_module_sha256": feature_module_hash,
        "raw_unique_counts": {"wav_files": len(seen_wav), "pcm_payloads": len(seen_pcm), "accel_xyz_sequences": len(seen_xyz)},
        "source_pcm16_rail_samples": sum(g["source_pcm16_rail_samples"] for g in groups),
        "source_groups_touching_pcm16_rails": sum(g["source_pcm16_rail_samples"] > 0 for g in groups),
        "chronology": chronology, "recording_log": log_check,
        "metadata_vs_wav_rms_or_peak_differences_at_2e_6_db": metadata_summary_diffs,
        "accel_frequency_band_validation": {
            "rule": "Match feature_spec §4.6 and App: blank if low >= Nyquist or high > Nyquist. The unpatched repository fallback can return a partial-band value that must not be treated as a full-band feature.",
            "nyquist_invalid_count_by_feature": dict(invalid_accel_band),
            "masked_partial_band_count_by_feature": dict(partial_accel_band_masked),
            "nyquist_hz_min_max": [min(g["accel_nyquist_hz"] for g in groups), max(g["accel_nyquist_hz"] for g in groups)],
        },
        "archived_vs_recomputed_diagnostics": {
            "comparison": "Number of groups with a difference >2e-6 in the original feature units or blank mismatch. Not an input for extraction; source CSV rounds exported XYZ to 4 decimals while the App operated on its original sensor precision.",
            "counts_by_feature": dict(archived_diff),
        },
        "noise_method": {
            "seed": SEED, "generator": "NumPy PCG64, SeedSequence([seed, condition ordinal 1..5, group index])",
            "signal": "Float64 add zero-mean, unit-RMS Gaussian noise scaled to full WAV RMS and target SNR. Three levels in each group share scaled versions of the same realization; different groups have independent deterministic seeds. No cross-group slicing.",
            "headroom": "When an additive sum exceeds the normalized PCM16 rails, apply one common linear gain to clean audio and noise, preserving SNR. No sample clipping, quantization, or audio output files.",
            "measurement": "20 log10(RMS(gain * clean) / RMS(mixture - gain * clean)); measured on the actual float64 feature-extractor input",
            "acceleration": "Extracted once from corresponding raw CSV; same 12 formatted accelerometer values at each SNR. Missing features remain blank.",
        },
        "noise_summary": {}, "groups": groups,
        "all_blank_columns": {name: [col for i, col in enumerate(header[2:], start=2)
                                     if all(row[i] == "" for row in rows)] for name, rows in tables.items()},
    }
    for target in SNR_LEVELS:
        stats = [g["white_noise"][str(target)] for g in groups]
        provenance["noise_summary"][str(target)] = {
            "actual_snr_db_min_max": [min(x["actual_snr_db"] for x in stats), max(x["actual_snr_db"] for x in stats)],
            "groups_with_headroom_gain": sum(x["linear_gain"] < 1.0 for x in stats),
            "min_linear_gain": min(x["linear_gain"] for x in stats),
            "pre_gain_out_of_pcm16_range_samples": sum(x["pre_gain_out_of_pcm16_range_samples"] for x in stats),
            "post_gain_out_of_pcm16_range_samples": sum(x["post_gain_out_of_pcm16_range_samples"] for x in stats),
        }
    publish(output, header, tables, provenance)
    print(json.dumps({"rows_per_csv": len(groups), "columns_per_csv": len(header),
                      "blank_columns": provenance["all_blank_columns"],
                      "noise_summary": provenance["noise_summary"],
                      "log_check": log_check}, indent=2))


def publish(output, header, tables, provenance):
    output.mkdir(exist_ok=True)
    require_plain(output, is_dir=True)
    filenames = [*(f"{name}.csv" for name in tables), "data_provenance.json"]
    for name in filenames:
        dest = output / name
        if dest.exists() or dest.is_symlink():
            require_plain(dest)
    # Stage all files for publication only after validating the full corpus.
    with TemporaryDirectory(prefix=".prepare_features-", dir=output) as staged_name:
        staged = Path(staged_name)
        for name, rows in tables.items():
            with (staged / f"{name}.csv").open("w", encoding="utf-8", newline="") as handle:
                writer = csv.writer(handle, lineterminator="\n")
                writer.writerow(header)
                writer.writerows(rows)
        (staged / "data_provenance.json").write_text(
            json.dumps(provenance, ensure_ascii=False, allow_nan=False, indent=2) + "\n", encoding="utf-8",
        )
        for name in filenames:
            candidate, dest = staged / name, output / name
            if not dest.is_file() or candidate.read_bytes() != dest.read_bytes():
                candidate.replace(dest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    args = parser.parse_args()
    try:
        prepare(args.source, HERE / "inputs")
    except (KeyError, TypeError, ValueError, OSError, wave.Error, csv.Error) as error:
        parser.exit(1, f"Validation/generation failed: {error}\n")


if __name__ == "__main__":
    main()
