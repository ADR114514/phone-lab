#!/usr/bin/env python3
"""Audit independent VibroDiag trials without changing input files or fitting a model."""
from __future__ import annotations

import argparse
from collections import Counter
import csv
import hashlib
import json
import math
from pathlib import Path
import wave

import numpy as np

from features import NUMERIC_COLUMNS, extract_from_files

MODEL = "matlab-rbf-ridge-VD-20261122-002-v1"
CONDITIONS = ["C1_NORMAL_LOW", "C2_NORMAL_HIGH", "C3_IMBALANCE", "C4_BLOCKED", "C5_HAIRDRYER"]
ASSET_SHA = "ef3e6cd8db7e0534187ed411b5c38491df56445cf1f84e45ea459de4748df600"
MODEL_SHA = "41b3ddac7f0fa3fa93613adb1cc4f7482beb9945eaa3e5d000928194e9a500d3"
HERE = Path(__file__).resolve().parent


def tab(path):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_table(path, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def sha(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def pcm(path):
    with wave.open(str(path), "rb") as sample:
        info = sample.getparams()
        assert (info.nchannels, info.sampwidth, info.framerate, info.nframes) == (1, 2, 44100, 661500), path
        return hashlib.sha256(sample.readframes(info.nframes)).hexdigest()


def xyz_hash(path):
    rows = tab(path)
    xyz = np.asarray([[float(row[k]) for k in ("x", "y", "z")] for row in rows], dtype="<f8")
    assert np.isfinite(xyz).all() and len(xyz) > 0, path
    return hashlib.sha256(xyz.tobytes()).hexdigest()


def value(s):
    return float(s) if s else float("nan")


def format_value(x):
    return format(x, ".15g") if math.isfinite(x) else ""


def collect(blind_root, training_root, out):
    assert sha(HERE / "results/vibro_cuda_model.mat") == MODEL_SHA, "Wrong frozen MATLAB file"
    root = blind_root.resolve()
    training = training_root.resolve()
    train_wavs, train_accel = set(), set()
    same_train_files = 0
    for condition in CONDITIONS:
        for g in range(1, 16):
            group = f"g{g:02d}"
            stem = f"{group}_{condition}"
            for suffix in ("audio.wav", "accel.csv", "features.csv", "meta.json"):
                rel = Path(condition, group, f"{stem}_{suffix}")
                assert sha(training / rel) == sha(root / rel), f"Original training file changed: {rel}"
                same_train_files += 1
            train_wavs.add(pcm(training / condition / group / f"{stem}_audio.wav"))
            train_accel.add(xyz_hash(training / condition / group / f"{stem}_accel.csv"))
    rows = tab(root / "blind_test_VD-20261122-002.csv")
    plan = (root / "blind/plan.txt").read_text(encoding="utf-8").splitlines()
    assert plan[0] == f"frozen-matlab:{MODEL}:{ASSET_SHA}"
    assert len(rows) == 15 and len(plan) == 16 and [int(r["trial_index"]) for r in rows] == list(range(15))
    assert Counter(r["condition_id_true"] for r in rows) == Counter({label: 3 for label in CONDITIONS})
    phone, raw, matches = [], [], []
    blind_wavs, blind_accel, seen = set(), set(), set()
    physical = []
    for index, row in enumerate(rows):
        truth = row["condition_id_true"]
        pred = row["condition_id_predicted"]
        assert truth == plan[index + 1] and pred in CONDITIONS and row["model"] == MODEL
        assert int(row["correct"]) == int(truth == pred) and row["predicted_confidence"] == ""
        path = (root / row["features_file"]).resolve()
        assert path.is_relative_to(root / "blind" / truth) and path not in seen
        seen.add(path)
        assert path.name.endswith("_features.csv")
        stem = path.name.removesuffix("_features.csv")
        details = load_json(path.with_name("matlab_prediction.json"))
        assert (details["model"], details["asset_sha256"], details["trial_index"]) == (MODEL, ASSET_SHA, index)
        assert (details["true_condition"], details["predicted_condition"], details["features_file"]) == (truth, pred, row["features_file"])
        scores = details["raw_scores"]
        assert details["score_labels"] == CONDITIONS and len(scores) == 5 and all(map(math.isfinite, scores))
        assert CONDITIONS[int(np.argmax(scores))] == pred
        meta = load_json(path.with_name(stem + "_meta.json"))
        assert meta["condition_id"] == truth and meta["blind_trial"] is True and meta["exclude_from_dataset"] is True
        assert meta["interrupted"] is False
        wav = path.with_name(stem + "_audio.wav")
        accel = path.with_name(stem + "_accel.csv")
        wav_id, acc_id = pcm(wav), xyz_hash(accel)
        assert wav_id not in train_wavs | blind_wavs, "Repeated PCM"
        assert acc_id not in train_accel | blind_accel, "Repeated acceleration sequence"
        blind_wavs.add(wav_id)
        blind_accel.add(acc_id)
        entry, = tab(path)
        assert entry["condition_id"] == truth and entry["file_stem"] == stem
        saved = {k: value(entry[k]) for k in NUMERIC_COLUMNS}
        recomputed = extract_from_files(wav, accel)
        assert all(math.isfinite(saved[k]) == math.isfinite(recomputed[k]) for k in NUMERIC_COLUMNS)
        phone.append({k: format_value(saved[k]) for k in NUMERIC_COLUMNS})
        raw.append({k: format_value(recomputed[k]) for k in NUMERIC_COLUMNS})
        err = {k: abs(saved[k] - recomputed[k]) for k in NUMERIC_COLUMNS if math.isfinite(saved[k])}
        matches.append({"trial": index + 1, "true_label": truth, "phone_label": pred,
                        "features_file": row["features_file"], "time_iso": row["timestamp_iso"],
                        "largest_feature_delta": max(err.values()), "largest_delta_feature": max(err, key=err.get),
                        **{f"phone_{k}": x for k, x in zip(CONDITIONS, scores)}})
        physical.append({"trial": index + 1, "true_label": truth, "audio_source": meta["audio"]["source"],
                         "duration_s": meta["duration_s"], "audio_rms_dbfs": recomputed["audio_rms_dbfs"],
                         "audio_peak_hz": recomputed["audio_peak_hz"], "accel_rate_hz": meta["accel"]["sample_rate_hz"],
                         "accel_rms": recomputed["accel_rms"], "max_audio_feature_delta": max(err[k] for k in NUMERIC_COLUMNS[:20]),
                         "max_accel_feature_delta": max(err[k] for k in NUMERIC_COLUMNS[20:] if k in err)})
    out.mkdir(parents=True, exist_ok=True)
    write_table(out / "phone_features.csv", phone)
    write_table(out / "recomputed_features.csv", raw)
    write_table(out / "phone_predictions.csv", matches)
    write_table(out / "signal_stats.csv", physical)
    print(f"Verified {same_train_files} unchanged training files; {len(rows)} unique blind PCM and acceleration sequences; exact model ID + asset SHA-256 in all rows.")
    print(f"Max raw-to-phone absolute feature difference: {max(x['largest_feature_delta'] for x in matches):.8g}")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("blind_export", type=Path)
    p.add_argument("original_training_export", type=Path)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    collect(args.blind_export, args.original_training_export, args.output)


if __name__ == "__main__":
    main()
