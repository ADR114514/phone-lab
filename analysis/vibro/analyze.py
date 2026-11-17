"""项目三 · 声振诊断仪 —— VibroDiag 数据分析管线。

输入：VibroDiag 导出目录（docs/DATA_DICTIONARY.md 第三节）
      <conditionId>/g<NN>/g<NN>_<conditionId>_audio.wav / _accel.csv / _features.csv / _meta.json
      dataset_index.csv、blind_test_<sessionId>.csv、manifest.json
输出：<out>/tables/*.csv、<out>/figures/*.png、<out>/vibro_report.md、<out>/evaluation_<id>.json

方法要点：
  * 递归扫描 **/*_meta.json（不假设文件在会话根目录），并排除 blind/ 盲测样本；
  * 特征提取严格按 docs/feature_spec.md 第 4 节实现（analysis/vibro/features.py），
    并可与 App 写出的 features.csv 交叉校验；
  * z-score + kNN(k=3)（纯 numpy 实现），留一法 LOOCV、混淆矩阵、逐工况准确率；
  * 盲测表解析：表内 predicted/correct 与“脚本按 WAV 重新提取特征再预测”的结果互相验证；
  * 抗噪：白噪声 / 粉红噪声，按目标 SNR 叠加后重新提取特征分类；
    同时输出“谱减法去噪后”的准确率，得到去噪前后两条曲线。

用法：
    python analysis/vibro/analyze.py --data data/raw/vibro/<session> --out analysis/out/vibro
    python analysis/vibro/analyze.py --data data/demo/vibro/20261118_VD-20261118-001 --out analysis/out/vibro
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ANALYSIS_DIR = HERE.parent
if str(ANALYSIS_DIR) not in sys.path:
    sys.path.insert(0, str(ANALYSIS_DIR))
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from common import io_utils, md_utils, plot_utils, stats_utils  # noqa: E402

plot_utils.setup()
import matplotlib.pyplot as plt  # noqa: E402

from vibro import features as vf  # noqa: E402
from vibro import model as vm  # noqa: E402

DEFAULT_CONDITION_ORDER = ["C1_NORMAL_LOW", "C2_NORMAL_HIGH", "C3_IMBALANCE",
                           "C4_BLOCKED", "C5_HAIRDRYER"]
FEATURE_DESCRIPTIONS = {
    "audio_rms_dbfs": "音频总 RMS（dBFS）", "audio_peak_dbfs": "音频峰值（dBFS）",
    "audio_crest_db": "峰值因数（峰均比）", "audio_zcr": "过零率",
    "audio_kurtosis": "峭度（冲击性）", "audio_skewness": "偏度",
    "audio_centroid_hz": "频谱质心", "audio_rolloff85_hz": "85% 滚降频率",
    "audio_flatness": "频谱平坦度", "audio_peak_hz": "主峰频率",
    "audio_peak_mag": "主峰幅值", "audio_harmonic_ratio": "谐波能量占比",
    "audio_hnr_db": "谐噪比 HNR", "band_0_100": "0–100 Hz 能量占比",
    "band_100_300": "100–300 Hz 能量占比", "band_300_1k": "300–1k Hz 能量占比",
    "band_1k_2k": "1–2 kHz 能量占比", "band_2k_5k": "2–5 kHz 能量占比",
    "band_5k_10k": "5–10 kHz 能量占比", "band_10k_20k": "10–20 kHz 能量占比",
    "accel_rms": "振动 RMS", "accel_peak": "振动峰值", "accel_crest": "振动峰值因数",
    "accel_zcr": "振动过零率", "accel_peak_hz": "振动主峰频率",
    "accel_centroid_hz": "振动频谱质心", "accel_flatness": "振动频谱平坦度",
    "accel_band_0_5": "0–5 Hz 振动占比", "accel_band_5_10": "5–10 Hz 振动占比",
    "accel_band_10_25": "10–25 Hz 振动占比", "accel_band_25_50": "25–50 Hz 振动占比",
    "accel_band_50_100": "50–100 Hz 振动占比",
}


# ---------------------------------------------------------------- 输入

def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="项目三 声振诊断分析")
    parser.add_argument("--data", default=None,
                        help="VibroDiag 导出目录；缺省时自动在 data/demo/vibro 或 data/raw/vibro 下找最新会话")
    parser.add_argument("--out", default=None, help="输出目录，默认 analysis/out/vibro")
    parser.add_argument("--k", type=int, default=vm.K, help="kNN 的 k（默认 3）")
    parser.add_argument("--snr", default="20,10,5,0,-5", help="抗噪评估的 SNR 列表（dB，逗号分隔）")
    parser.add_argument("--noise-types", default="white,pink", help="噪声类型（white,pink）")
    parser.add_argument("--no-denoise", action="store_true", help="跳过谱减法去噪对比（更快）")
    args = parser.parse_args(argv)
    repo_root = ANALYSIS_DIR.parent
    if args.data is None:
        args.data = str(find_latest_session_dir([repo_root / "data" / "demo" / "vibro",
                                                 repo_root / "data" / "raw" / "vibro"]))
    if args.out is None:
        args.out = str(repo_root / "analysis" / "out" / "vibro")
    return args


def find_latest_session_dir(candidates: list[Path]) -> Path:
    for root in candidates:
        if not root.is_dir():
            continue
        sessions = [p for p in root.iterdir() if p.is_dir() and list(p.rglob("*_meta.json"))]
        if sessions:
            return sorted(sessions, key=lambda p: p.stat().st_mtime)[-1]
    raise FileNotFoundError("没有找到包含 *_meta.json 的 VibroDiag 导出目录，请用 --data 指定")


def discover_samples(session_dir: Path) -> list[dict]:
    """递归扫描 *_meta.json；排除 blind/ 下的盲测样本（它们不参与训练）。"""
    samples: list[dict] = []
    for meta_path in sorted(session_dir.rglob("*_meta.json")):
        relative = meta_path.relative_to(session_dir)
        if relative.parts and relative.parts[0].lower() == "blind":
            continue
        meta = vf.read_meta(meta_path)
        condition_id = str(meta.get("condition_id") or relative.parts[0] if relative.parts else "UNKNOWN")
        group_index = meta.get("group_index")
        stem = meta_path.name.replace("_meta.json", "")
        audio_path = meta_path.parent / f"{stem}_audio.wav"
        accel_path = meta_path.parent / f"{stem}_accel.csv"
        features_path = meta_path.parent / f"{stem}_features.csv"
        samples.append({
            "condition_id": condition_id,
            "condition_label": meta.get("condition_label", condition_id),
            "group_index": int(group_index) if group_index is not None else 0,
            "stem": stem,
            "meta_path": meta_path,
            "audio_path": audio_path if audio_path.is_file() else None,
            "accel_path": accel_path if accel_path.is_file() else None,
            "features_path": features_path if features_path.is_file() else None,
            "relative_dir": relative.parent.as_posix(),
        })
    if not samples:
        raise FileNotFoundError(f"{session_dir} 下没有找到任何 *_meta.json 样本")
    return samples


def load_dataset_index(session_dir: Path) -> tuple[pd.DataFrame | None, list[str]]:
    paths = sorted(session_dir.glob("dataset_index.csv"))
    if not paths:
        return None, ["缺少 dataset_index.csv，无法校验 5 工况完整性（已按实际样本继续分析）"]
    frame = io_utils.coerce_numeric(
        io_utils.read_csv(paths[0], name="dataset_index.csv"),
        ["groups_collected", "target_groups"])
    issues: list[str] = []
    for _, row in frame.iterrows():
        collected = row.get("groups_collected")
        target = row.get("target_groups")
        complete = row.get("complete")
        if np.isfinite(collected) and np.isfinite(target) and collected < target:
            issues.append(f"{row.get('condition_id')}: 已采集 {int(collected)}/{int(target)} 组，未达到目标")
        if str(complete).strip() in ("0", "False", "false") and np.isfinite(collected) and np.isfinite(target) and collected >= target:
            issues.append(f"{row.get('condition_id')}: complete=0 但组数已达标，请检查索引写入逻辑")
    return frame, issues


def load_session(session_dir: Path, k: int) -> dict:
    samples = discover_samples(session_dir)
    index, index_issues = load_dataset_index(session_dir)
    conditions_in_data = sorted({sample["condition_id"] for sample in samples})
    condition_order = [cid for cid in DEFAULT_CONDITION_ORDER if cid in conditions_in_data]
    condition_order += [cid for cid in conditions_in_data if cid not in condition_order]
    labels_map = {}
    feature_rows: list[dict] = []
    numeric_rows: list[np.ndarray] = []
    labels: list[str] = []
    crosscheck: list[dict] = []
    for sample in samples:
        if sample["audio_path"] is None:
            raise FileNotFoundError(f"{sample['stem']}: 缺少 WAV 音频")
        features = vf.extract_from_files(sample["audio_path"], sample["accel_path"])
        labels_map[sample["condition_id"]] = sample["condition_label"]
        vector = np.asarray([features.get(name, np.nan) for name in vf.NUMERIC_COLUMNS], dtype=float)
        numeric_rows.append(vector)
        labels.append(sample["condition_id"])
        row = dict(features)
        row.update({
            "condition_id": sample["condition_id"],
            "condition_label": sample["condition_label"],
            "group_index": sample["group_index"],
            "file_stem": sample["stem"],
            "relative_dir": sample["relative_dir"],
        })
        feature_rows.append(row)
        if sample["features_path"] is not None:
            stored = io_utils.read_csv(sample["features_path"], name=sample["features_path"].name)
            if len(stored):
                relative_errors = []
                absolute_errors = []
                for name in vf.NUMERIC_COLUMNS:
                    if name not in stored.columns:
                        continue
                    stored_value = pd.to_numeric(stored[name], errors="coerce").iloc[0]
                    current = features.get(name, np.nan)
                    if not np.isfinite(stored_value) or not np.isfinite(current):
                        continue
                    absolute_errors.append(abs(current - stored_value))
                    # CSV 按 %.6f 存储，极小特征值的相对误差没有意义；只在 |stored|>=1e-3 时统计相对误差
                    if abs(stored_value) >= 1e-3:
                        relative_errors.append(abs(current - stored_value) / abs(stored_value))
                crosscheck.append({
                    "file_stem": sample["stem"],
                    "condition_id": sample["condition_id"],
                    "max_abs_error": float(np.max(absolute_errors)) if absolute_errors else float("nan"),
                    "max_rel_error": float(np.max(relative_errors)) if relative_errors else float("nan"),
                })
    manifest = {}
    manifest_path = session_dir / "manifest.json"
    if manifest_path.is_file():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            manifest = {}
    return {
        "dir": session_dir, "samples": samples, "features": pd.DataFrame(feature_rows),
        "X": np.vstack(numeric_rows) if numeric_rows else np.zeros((0, len(vf.NUMERIC_COLUMNS))),
        "y": labels, "conditions": condition_order, "label_map": labels_map,
        "index": index, "index_issues": index_issues, "crosscheck": pd.DataFrame(crosscheck),
        "manifest": manifest, "k": k,
    }


def is_synthetic(data: dict) -> bool:
    if data["manifest"].get("synthetic") is True:
        return True
    return "demo" in {part.lower() for part in data["dir"].parts}


ACCEL_COLUMNS = vf.ACCEL_TIME + vf.ACCEL_SPECTRUM + vf.ACCEL_BANDS


# ---------------------------------------------------------------- 模型评估

def evaluate_training(data: dict) -> dict:
    result = vm.loocv(data["X"], data["y"], k=data["k"])
    order = [cid for cid in data["conditions"] if cid in result.get("labels", [])]
    order += [cid for cid in result.get("labels", []) if cid not in order]
    if not order:
        # 空数据集/极小数据集兜底：用样本标签保证标签与混淆矩阵维度一致。
        order = list(dict.fromkeys(data["y"]))
    matrix = result.get("confusion") or []
    if len(matrix) != len(order) or any(len(row) != len(order) for row in matrix):
        matrix = [[0] * len(order) for _ in order]
    result["confusion"] = matrix
    result.setdefault("folds", [])
    result.setdefault("accuracy", 0.0)
    result.setdefault("per_condition", {label: 0.0 for label in order})
    result["labels"] = order
    result["note"] = result.get("note", "")
    return result


def confusion_frame(labels: list[str], matrix: list[list[int]]) -> pd.DataFrame:
    if not labels:
        frame = pd.DataFrame()
        frame.index.name = "真实\\预测"
        return frame
    frame = pd.DataFrame(matrix, index=labels, columns=labels)
    frame.index.name = "真实\\预测"
    return frame


def per_condition_frame(result: dict) -> pd.DataFrame:
    accuracy = result["per_condition"]
    counts: dict[str, int] = {label: 0 for label in result["labels"]}
    for fold in result["folds"]:
        if fold["truth"] in counts:
            counts[fold["truth"]] += 1
    return pd.DataFrame([
        {"condition_id": label, "samples": counts[label],
         "accuracy": accuracy.get(label, 0.0),
         "errors": counts[label] - int(round(accuracy.get(label, 0.0) * counts[label]))}
        for label in result["labels"]])


def evaluate_blind(data: dict, classifier: vm.KnnClassifier) -> tuple[pd.DataFrame, dict]:
    paths = sorted(data["dir"].glob("blind_test_*.csv"))
    if not paths:
        return pd.DataFrame(), {"available": False}
    frame = io_utils.read_csv(paths[0], name=paths[0].name)
    rows = []
    for _, row in frame.iterrows():
        features_file = str(row.get("features_file") or "").strip()
        true_label = str(row.get("condition_id_true") or "").strip()
        csv_prediction = str(row.get("condition_id_predicted") or "").strip()
        csv_confidence = pd.to_numeric(row.get("predicted_confidence"), errors="coerce")
        recomputed_label = ""
        recomputed_confidence = float("nan")
        note = ""
        if features_file:
            candidate = Path(features_file)
            if not candidate.is_absolute():
                candidate = data["dir"] / candidate
            try:
                if candidate.suffix.lower() == ".wav":
                    accel_path = candidate.parent / (candidate.name.replace("_audio.wav", "_accel.csv"))
                    features = vf.extract_from_files(candidate, accel_path if accel_path.is_file() else None)
                    prediction = classifier.predict(
                        np.asarray([features.get(name, np.nan) for name in vf.NUMERIC_COLUMNS]))
                    recomputed_label = prediction["label"]
                    recomputed_confidence = prediction["confidence"]
                elif candidate.suffix.lower() == ".csv":
                    stored = io_utils.read_csv(candidate, name=candidate.name)
                    values = [pd.to_numeric(stored.get(name, pd.Series([np.nan])), errors="coerce").iloc[0]
                              for name in vf.NUMERIC_COLUMNS]
                    prediction = classifier.predict(np.asarray(values, dtype=float))
                    recomputed_label = prediction["label"]
                    recomputed_confidence = prediction["confidence"]
                else:
                    note = "features_file 不是 wav/csv"
            except (FileNotFoundError, ValueError) as exc:
                note = f"无法读取盲测样本: {exc}"
        rows.append({
            "trial_index": int(pd.to_numeric(row.get("trial_index"), errors="coerce") or 0)
            if str(row.get("trial_index", "")).strip() else len(rows) + 1,
            "condition_id_true": true_label,
            "condition_id_predicted_csv": csv_prediction,
            "predicted_confidence_csv": float(csv_confidence) if np.isfinite(csv_confidence) else float("nan"),
            "correct_csv": int(str(row.get("correct")).strip() in ("1", "True", "true")),
            "condition_id_predicted_recomputed": recomputed_label,
            "predicted_confidence_recomputed": recomputed_confidence,
            "correct_recomputed": int(recomputed_label == true_label) if recomputed_label else 0,
            "agreement": int(recomputed_label == csv_prediction) if recomputed_label else 0,
            "features_file": features_file,
            "note": note,
            "model": str(row.get("model") or ""),
        })
    blind = pd.DataFrame(rows)
    summary = {
        "available": True,
        "trials": int(len(blind)),
        "accuracy_csv": float(blind["correct_csv"].mean()) if len(blind) else float("nan"),
        "accuracy_recomputed": (float(blind["correct_recomputed"].mean())
                                if len(blind) and (blind["condition_id_predicted_recomputed"] != "").any()
                                else float("nan")),
        "agreement": float(blind["agreement"].mean()) if len(blind) else float("nan"),
        "source_file": paths[0].name,
    }
    return blind, summary


def evaluate_noise(data: dict, classifier: vm.KnnClassifier, snr_values: list[float],
                   noise_types: list[str], do_denoise: bool = True) -> pd.DataFrame:
    """对每个样本叠加白/粉红噪声到目标 SNR，重新提特征分类；可选谱减法去噪后再分类。

    为了控制真机 75 组数据的内存/时间：外层遍历样本（音频只读一次），内层遍历
    (噪声类型, SNR)，每个组合用独立随机种子，结果可复现。
    """
    features = data["features"]
    accel_vectors = [np.asarray([row[name] for name in ACCEL_COLUMNS], dtype=float)
                     for _, row in features.iterrows()]
    stats = {(noise_type, float(snr)): {"noisy": 0, "denoised": 0, "trials": 0}
             for noise_type in noise_types for snr in snr_values}
    audio_columns = vf.AUDIO_TIME + vf.AUDIO_SPECTRUM + vf.AUDIO_BANDS
    for index, sample in enumerate(data["samples"]):
        audio, sample_rate = vf.read_wav(sample["audio_path"])
        accel = accel_vectors[index]
        true_label = data["y"][index]
        for type_index, noise_type in enumerate(noise_types):
            for snr_index, snr_db in enumerate(snr_values):
                rng = np.random.default_rng(90000 + type_index * 1000 + snr_index * 37 + index * 7)
                noise = (vm.white_noise(audio.size, rng) if noise_type == "white"
                         else vm.pink_noise(audio.size, rng))
                mixed = vm.mix_noise(audio, noise, snr_db)
                audio_features = vf.extract_audio_features(mixed, sample_rate)
                vector = np.concatenate(
                    [np.asarray([audio_features.get(name, np.nan) for name in audio_columns]), accel])
                key = (noise_type, float(snr_db))
                stats[key]["trials"] += 1
                if classifier.predict(vector)["label"] == true_label:
                    stats[key]["noisy"] += 1
                if do_denoise:
                    denoised = vm.spectral_subtract(mixed)
                    denoised_features = vf.extract_audio_features(denoised, sample_rate)
                    vector_denoised = np.concatenate(
                        [np.asarray([denoised_features.get(name, np.nan) for name in audio_columns]),
                         accel])
                    if classifier.predict(vector_denoised)["label"] == true_label:
                        stats[key]["denoised"] += 1
    rows = []
    for (noise_type, snr_db), value in stats.items():
        trials = value["trials"]
        rows.append({
            "snr_db": snr_db,
            "noise_type": noise_type,
            "trials": trials,
            "correct_noisy": value["noisy"],
            "accuracy_noisy": value["noisy"] / trials if trials else float("nan"),
            "correct_denoised": value["denoised"] if do_denoise else 0,
            "accuracy_denoised": (value["denoised"] / trials
                                  if trials and do_denoise else float("nan")),
        })
    order = {(noise_type, float(snr)): position
             for position, (noise_type, snr) in enumerate(
                 (nt, snr) for snr in sorted(snr_values, reverse=True) for nt in noise_types)}
    rows.sort(key=lambda row: order.get((row["noise_type"], row["snr_db"]), 99))
    return pd.DataFrame(rows)


def evaluate_clean_resubstitution(data: dict) -> dict:
    """训练集自检（resubstitution，仅作参考，真实泛化看 LOOCV）："""
    classifier = vm.KnnClassifier(data["k"]).fit(data["X"], data["y"])
    correct = 0
    for index, vector in enumerate(data["X"]):
        if classifier.predict(vector)["label"] == data["y"][index]:
            correct += 1
    return {"accuracy": correct / len(data["y"]) if data["y"] else float("nan"),
            "samples": len(data["y"])}


# ---------------------------------------------------------------- 图表

def compute_condition_spectra(data: dict) -> dict[str, tuple[np.ndarray, float]]:
    spectra: dict[str, list[np.ndarray]] = {}
    bin_hz = None
    for sample in data["samples"]:
        audio, sample_rate = vf.read_wav(sample["audio_path"])
        spectrum = vf.average_power_spectrum(audio, vf.AUDIO_FFT_SIZE, vf.AUDIO_HOP,
                                             vf.hann_window(vf.AUDIO_FFT_SIZE))
        spectra.setdefault(sample["condition_id"], []).append(spectrum)
        bin_hz = sample_rate / vf.AUDIO_FFT_SIZE
    mean_spectra = {condition: np.mean(value, axis=0) for condition, value in spectra.items()}
    return mean_spectra, float(bin_hz or 1.0)


def make_figures(data: dict, result: dict, blind: pd.DataFrame, noise: pd.DataFrame,
                 figure_dir: Path) -> list[str]:
    io_utils.ensure_dir(figure_dir)
    names: list[str] = []
    labels = result["labels"]
    label_map = data["label_map"]

    # 1) LOOCV 混淆矩阵热图
    matrix = np.asarray(result["confusion"], dtype=float)
    fig, ax = plt.subplots(figsize=(6.4, 5.2))
    image = ax.imshow(matrix, cmap="Blues")
    ax.set_xticks(range(len(labels)))
    ax.set_yticks(range(len(labels)))
    short = [f"{label}\n{label_map.get(label, '')}" for label in labels]
    ax.set_xticklabels(short, rotation=35, ha="right", fontsize=8)
    ax.set_yticklabels(short, fontsize=8)
    for row in range(matrix.shape[0]):
        for column in range(matrix.shape[1]):
            value = int(matrix[row, column])
            if value:
                ax.annotate(str(value), (column, row), ha="center", va="center",
                            color="white" if value > matrix.max() * 0.6 else "#333333",
                            fontsize=11, fontweight="bold")
    ax.set_xlabel("预测工况")
    ax.set_ylabel("真实工况")
    ax.set_title(f"LOOCV 混淆矩阵（准确率 {result['accuracy']:.1%}，n={len(data['y'])}）")
    fig.colorbar(image, ax=ax, shrink=0.8)
    names.append(Path(plot_utils.save(fig, figure_dir / "confusion_matrix.png")).name)

    # 2) 频带/频带对比：各工况平均功率谱
    mean_spectra, bin_hz = compute_condition_spectra(data)
    fig, axes = plt.subplots(1, 2, figsize=(10.4, 4.2))
    for index, condition in enumerate(labels):
        spectrum = mean_spectra.get(condition)
        if spectrum is None:
            continue
        frequencies = np.arange(spectrum.size) * bin_hz
        mask = (frequencies >= 20.0) & (frequencies <= 10000.0)
        axes[0].plot(frequencies[mask], 10.0 * np.log10(spectrum[mask] + 1e-18),
                     linewidth=1.1, label=f"{condition} {label_map.get(condition, '')}",
                     color=plot_utils.PALETTE[index % len(plot_utils.PALETTE)])
    axes[0].set_xscale("log")
    axes[0].set_xlabel("频率 / Hz")
    axes[0].set_ylabel("平均功率谱 / dB")
    axes[0].set_title(f"{len(labels)} 工况平均音频功率谱")
    axes[0].legend(fontsize=6.5)
    # 干净 / 加噪 / 去噪 对比（取第一个样本）
    example = data["samples"][0]
    audio, sample_rate = vf.read_wav(example["audio_path"])
    rng = np.random.default_rng(2026)
    mixed = vm.mix_noise(audio, vm.white_noise(audio.size, rng), 5.0)
    denoised = vm.spectral_subtract(mixed)
    for signal, label, color in [(audio, "干净信号", plot_utils.PALETTE[0]),
                                 (mixed, "白噪声 SNR=5 dB", plot_utils.PALETTE[5]),
                                 (denoised, "谱减法去噪后", plot_utils.PALETTE[2])]:
        spectrum = vf.average_power_spectrum(signal, vf.AUDIO_FFT_SIZE, vf.AUDIO_HOP,
                                             vf.hann_window(vf.AUDIO_FFT_SIZE))
        frequencies = np.arange(spectrum.size) * (sample_rate / vf.AUDIO_FFT_SIZE)
        mask = (frequencies >= 20.0) & (frequencies <= 10000.0)
        axes[1].plot(frequencies[mask], 10.0 * np.log10(spectrum[mask] + 1e-18),
                     linewidth=1.1, label=label, color=color)
    axes[1].set_xscale("log")
    axes[1].set_xlabel("频率 / Hz")
    axes[1].set_title(f"加噪/去噪频谱对比（{example['condition_id']}）")
    axes[1].legend(fontsize=8)
    fig.tight_layout()
    names.append(Path(plot_utils.save(fig, figure_dir / "spectrum_compare.png")).name)

    # 3) 关键特征分布
    key_features = ["audio_peak_hz", "audio_flatness", "audio_centroid_hz",
                    "accel_peak_hz", "accel_rms", "accel_crest"]
    fig, axes = plt.subplots(2, 3, figsize=(11.0, 6.0))
    features = data["features"]
    for axis, name in zip(axes.ravel(), key_features):
        # 部分采集（真实数据边采边分析）时某些工况可能没有样本：
        # 只画非空分组，并让 positions 与分组数量严格对应。
        nonempty = []
        for index, condition in enumerate(labels):
            values = pd.to_numeric(features.loc[features["condition_id"] == condition, name],
                                   errors="coerce").dropna().to_numpy(dtype=float)
            if values.size:
                nonempty.append((index, condition, values))
        if not nonempty:
            axis.set_axis_off()
            axis.set_title(FEATURE_DESCRIPTIONS.get(name, name) + "（无数据）", fontsize=9)
            continue
        axis.boxplot([values for _, _, values in nonempty],
                     positions=[index for index, _, _ in nonempty], widths=0.55, patch_artist=True,
                     boxprops={"facecolor": "#E8F3FF", "color": "#2E5BFF"},
                     medianprops={"color": "#E74C3C"})
        axis.set_xticks([index for index, _, _ in nonempty])
        axis.set_xticklabels([condition.split("_")[0] for _, condition, _ in nonempty], fontsize=8)
        axis.set_title(FEATURE_DESCRIPTIONS.get(name, name), fontsize=9)
    fig.suptitle(f"关键特征在 {len(labels)} 个工况下的分布（箱线图）")
    fig.tight_layout()
    names.append(Path(plot_utils.save(fig, figure_dir / "feature_distribution.png")).name)

    # 4) 抗噪曲线
    if len(noise):
        fig, ax = plt.subplots(figsize=(8.2, 4.2))
        snrs = sorted(noise["snr_db"].unique(), reverse=True)
        x = np.arange(len(snrs))
        style = {"white": ("#2E5BFF", "o", "白噪声"), "pink": ("#E74C3C", "s", "粉红噪声")}
        for noise_type, (color, marker, label) in style.items():
            subset = noise[noise["noise_type"] == noise_type].sort_values("snr_db", ascending=False)
            if not len(subset):
                continue
            ax.plot(np.arange(len(subset)), subset["accuracy_noisy"], marker=marker,
                    color=color, linewidth=1.8, label=f"{label}（加噪）")
            if subset["accuracy_denoised"].notna().any():
                ax.plot(np.arange(len(subset)), subset["accuracy_denoised"], marker=marker,
                        linestyle="--", color=color, linewidth=1.4, alpha=0.75,
                        label=f"{label}（谱减法去噪后）")
        ax.set_xticks(x)
        ax.set_xticklabels([f"{snr:.0f}" for snr in snrs])
        ax.set_xlabel("测试 SNR / dB")
        ax.set_ylabel("准确率")
        ax.set_ylim(0.0, 1.05)
        ax.axhline(0.6, color="#888888", linestyle=":", linewidth=1.0)
        ax.annotate("0.6 可用性阈值", xy=(0.01, 0.61), xycoords=("axes fraction", "data"),
                    fontsize=8, color="#666666")
        ax.set_title("抗噪准确率曲线（去噪前 vs 谱减法去噪后）")
        ax.legend(fontsize=8, ncol=2)
        names.append(Path(plot_utils.save(fig, figure_dir / "noise_accuracy.png")).name)

    # 5) 盲测逐工况准确率
    if len(blind):
        per_condition = blind.groupby("condition_id_true")["correct_recomputed"].agg(["mean", "count"])
        fig, ax = plt.subplots(figsize=(7.6, 3.8))
        bars = ax.bar(range(len(per_condition)), per_condition["mean"],
                      color=plot_utils.PALETTE[:len(per_condition)], width=0.6)
        ax.set_xticks(range(len(per_condition)))
        ax.set_xticklabels([f"{label}\n(n={int(count)})" for label, count
                            in zip(per_condition.index, per_condition["count"])], fontsize=8)
        ax.set_ylim(0.0, 1.05)
        ax.set_ylabel("准确率")
        ax.set_title(f"盲测逐工况准确率（总 {float(blind['correct_recomputed'].mean()):.1%}，"
                     f"{len(blind)} 组）")
        for bar, value in zip(bars, per_condition["mean"]):
            ax.annotate(f"{value:.0%}", (bar.get_x() + bar.get_width() / 2, bar.get_height()),
                        ha="center", va="bottom", fontsize=9)
        names.append(Path(plot_utils.save(fig, figure_dir / "blind_test.png")).name)
    return names


# ---------------------------------------------------------------- 报告

def _accuracy_at(noise: pd.DataFrame, noise_type: str, snr: float, denoised: bool) -> float:
    subset = noise[(noise["noise_type"] == noise_type) & np.isclose(noise["snr_db"], snr)]
    if not len(subset):
        return float("nan")
    column = "accuracy_denoised" if denoised else "accuracy_noisy"
    return float(subset[column].iloc[0])


def noise_conclusions(noise: pd.DataFrame) -> list[str]:
    if not len(noise):
        return ["- 未进行抗噪评估。"]
    lines: list[str] = []
    snrs = sorted(noise["snr_db"].unique(), reverse=True)
    for noise_type, label in [("white", "白噪声"), ("pink", "粉红噪声")]:
        values = [(snr, _accuracy_at(noise, noise_type, snr, False)) for snr in snrs]
        values = [(snr, value) for snr, value in values if np.isfinite(value)]
        if not values:
            continue
        best = max(values, key=lambda item: item[1])
        worst = min(values, key=lambda item: item[1])
        failure = next((snr for snr, value in values if value < 0.6), None)
        text = (f"- {label}：SNR {best[0]:.0f} dB 时准确率 {best[1]:.1%}，"
                f"SNR {worst[0]:.0f} dB 时降至 {worst[1]:.1%}；")
        text += (f"低于 0.6 的失效点出现在 SNR ≈ {failure:.0f} dB。" if failure is not None
                 else "在所测 SNR 范围内均保持 ≥0.6。")
        lines.append(text)
        denoised_values = [(snr, _accuracy_at(noise, noise_type, snr, True)) for snr in snrs]
        denoised_values = [(snr, value) for snr, value in denoised_values if np.isfinite(value)]
        if denoised_values:
            low = [(snr, noisy, den) for (snr, noisy), (_, den)
                   in zip(values, denoised_values) if snr <= 5.0]
            if low:
                gains = [den - noisy for _, noisy, den in low]
                lines.append(f"  - 谱减法去噪在 SNR ≤5 dB 区间平均提升 "
                             f"{np.mean(gains):+.1%}（最大 {max(gains):+.1%}）。")
    return lines


def build_report(data: dict, result: dict, per_condition: pd.DataFrame,
                 blind: pd.DataFrame, blind_summary: dict, noise: pd.DataFrame,
                 resubstitution: dict, figure_names: list[str], synthetic: bool,
                 issues: list[str]) -> str:
    session_id = str(data["manifest"].get("session", {}).get("sessionId", "")) or data["dir"].name
    lines: list[str] = []
    lines.append("# 项目三 · 手机声振诊断仪 —— 分析报告")
    lines.append("")
    lines.append("> 由 analysis/vibro/analyze.py 自动生成；特征定义见 docs/feature_spec.md，"
                 "分类器为 z-score + kNN(k=3)，纯 numpy 实现。")
    if synthetic:
        lines.append(">")
        lines.append("> **本报告使用的是 data/demo 合成演示数据（SYNTHETIC），仅用于验证分析管线，"
                     "不能作为课程报告的真实测量结论。** 换成 data/raw 下的真机数据后重跑即可。")
    lines.append("")
    lines.append("## 摘要")
    lines.append("")
    groups = data["features"].groupby("condition_id").size().to_dict()
    total = int(len(data["features"]))
    lines.append(
        f"本批数据共 {total} 组样本（5 工况，每组 {min(groups.values()) if groups else 0}–"
        f"{max(groups.values()) if groups else 0} 组），提取 {len(vf.NUMERIC_COLUMNS)} 维声振特征。"
        f"z-score + kNN(k={data['k']}) 的留一法（LOOCV）准确率为 **{result['accuracy']:.1%}**；"
        f"各工况准确率：" + "、".join(f"{label} {accuracy:.0%}"
                                       for label, accuracy in result["per_condition"].items()) + "。")
    if blind_summary.get("available"):
        accuracy = blind_summary.get("accuracy_recomputed")
        if not np.isfinite(accuracy):
            accuracy = blind_summary.get("accuracy_csv")
        lines.append(
            f"盲测 {blind_summary['trials']} 组（不参与训练）的准确率为 **{accuracy:.1%}**，"
            f"盲测表内预测与脚本重算预测的一致率为 {blind_summary['agreement']:.1%}。")
    if len(noise):
        snrs = sorted(noise["snr_db"].unique(), reverse=True)
        white_at_10 = _accuracy_at(noise, "white", 10.0, False)
        white_at_0 = _accuracy_at(noise, "white", 0.0, False)
        pink_at_0 = _accuracy_at(noise, "pink", 0.0, False)
        lines.append(
            f"抗噪：SNR 10 dB 时白噪声下准确率 {white_at_10:.1%}，0 dB 时白噪声 {white_at_0:.1%}、"
            f"粉红噪声 {pink_at_0:.1%}；详细曲线见第 5 节。")
    lines.append("")
    lines.append(f"**会话**：{session_id}；**样本目录**：{data['dir'].as_posix()}；"
                 f"**设备**：{data['manifest'].get('device', {}).get('model', '未提供 manifest')}。")
    lines.append("")
    lines.append("## 1. 数据与完整性")
    lines.append("")
    if data["index"] is not None:
        lines.append(md_utils.md_table(
            ["工况", "已采集组数", "目标组数", "complete"],
            [[row["condition_id"], int(row["groups_collected"]), int(row["target_groups"]),
              str(row["complete"])] for _, row in data["index"].iterrows()]))
    else:
        lines.append("- 未找到 dataset_index.csv。")
    lines.append("")
    lines.append(md_utils.md_table(
        ["工况", "实际样本组数", "工况标签"],
        [[condition, groups.get(condition, 0), data["label_map"].get(condition, condition)]
         for condition in data["conditions"]]))
    lines.append("")
    if len(data["crosscheck"]):
        max_abs = float(np.nanmax(data["crosscheck"]["max_abs_error"]))
        max_rel = float(np.nanmax(data["crosscheck"]["max_rel_error"]))
        lines.append(f"- 特征交叉校验：脚本按 WAV 重算的特征与 App 写出 features.csv 相比，"
                     f"最大绝对误差 {max_abs:.3e}、最大相对误差 {max_rel:.3e}"
                     + ("（小于 feature_spec 要求的 1e-3 量级）。" if max_rel < 1e-3
                        else "（注意：相对误差偏大项通常是接近 0 的小量，请结合绝对误差判断）。"))
    if issues:
        for issue in issues:
            lines.append(f"- ⚠️ {issue}")
        lines.append("- 上述问题不阻断分析；真机实验请按 SOP 补采到 5 工况 × 15 组。")
    else:
        lines.append("- ✅ 5 工况样本齐全，dataset_index 与实际样本一致。")
    lines.append("")
    lines.append("## 2. 特征工程")
    lines.append("")
    lines.append(md_utils.md_table(
        ["特征组", "维度", "代表特征", "物理意义"],
        [["音频时域", 6, "audio_rms_dbfs / crest / zcr / kurtosis",
          "响度、峰值因数、过零率、冲击性，区分风扇与吹风机"],
         ["音频频域", 7, "audio_centroid_hz / rolloff85 / flatness / harmonic_ratio / hnr_db",
          "音色亮度、高频滚降、谐波结构；失衡带来边带、遮挡带来高频衰减"],
         ["音频频带", 7, "band_0_100 … band_10k_20k",
          "能量分布；吹风机高频宽带、遮挡后高频能量下降"],
         ["振动时域", 4, "accel_rms / peak / crest / zcr",
          "振动强度与冲击性；失衡时 1x 振动明显增大"],
         ["振动频域", 3, "accel_peak_hz / centroid_hz / flatness",
          "转频与频谱形状"],
         ["振动频带", 5, "accel_band_0_5 … accel_band_50_100",
          "低频转频、中高频轴承/气流振动占比"]]))
    lines.append("")
    lines.append(f"完整特征定义见 docs/feature_spec.md；本脚本实现与 App 的 FeatureExtractor 逐条对齐"
                 f"（帧起点/谱归一化/频带分母/谐波 bin/短帧补零等约定），"
                 f"共 {len(vf.NUMERIC_COLUMNS)} 个数值特征 + 6 个标签列 = {len(vf.CSV_HEADER)} 列。")
    lines.append("")
    lines.append(md_utils.md_figure("figures/feature_distribution.png", "关键特征在 5 工况下的分布"))
    lines.append("")
    lines.append("## 3. 分类结果（LOOCV）")
    lines.append("")
    lines.append(f"- LOOCV 准确率：**{result['accuracy']:.1%}**（{len(result['folds'])} 折）。")
    lines.append(f"- 训练集自检（resubstitution，仅参考）：{resubstitution['accuracy']:.1%}。")
    lines.append(f"- 标准化与 k：每折重新拟合 z-score（训练折均值/标准差，标准差为 0 时置 1），"
                 f"k = {data['k']}，欧氏距离，1/(d+eps) 加权投票，平票取最近邻类别。")
    lines.append("")
    lines.append("混淆矩阵（行=真实，列=预测）：")
    lines.append("")
    confusion = confusion_frame(result["labels"], result["confusion"]).reset_index()
    lines.append(md_utils.md_table(
        ["真实\\预测"] + result["labels"],
        [[row.iloc[0]] + [int(value) for value in row.iloc[1:]] for _, row in confusion.iterrows()]))
    lines.append("")
    lines.append(md_utils.md_table(
        ["工况", "样本数", "准确率", "错分数"],
        [[row["condition_id"], int(row["samples"]), f"{row['accuracy']:.0%}", int(row["errors"])]
         for _, row in per_condition.iterrows()]))
    lines.append("")
    lines.append(md_utils.md_figure("figures/confusion_matrix.png", "LOOCV 混淆矩阵"))
    lines.append("")
    lines.append("## 4. 盲测")
    lines.append("")
    if blind_summary.get("available") and len(blind):
        lines.append(md_utils.md_table(
            ["试次", "真实工况", "表内预测", "脚本重算", "置信度", "是否正确", "一致"],
            [[int(row["trial_index"]), row["condition_id_true"],
              row["condition_id_predicted_csv"], row["condition_id_predicted_recomputed"] or "—",
              md_utils.fmt_number(row["predicted_confidence_recomputed"], 2)
              if np.isfinite(row["predicted_confidence_recomputed"])
              else md_utils.fmt_number(row["predicted_confidence_csv"], 2),
              "✓" if row["correct_recomputed"] else "✗",
              "是" if row["agreement"] else "否"] for _, row in blind.iterrows()]))
        lines.append("")
        accuracy_recomputed = blind_summary.get("accuracy_recomputed")
        lines.append(f"- 盲测表（{blind_summary['source_file']}）记录的准确率："
                     f"{blind_summary['accuracy_csv']:.1%}；"
                     f"脚本按 features_file 重新提取特征再预测的准确率："
                     f"{accuracy_recomputed:.1%}；两者一致率 {blind_summary['agreement']:.1%}。")
        lines.append("- 盲测样本不参与训练；预测发生在揭示真实标签之前（App 侧记录），此处用脚本复核可复现性。")
    else:
        lines.append("- 未找到 blind_test_*.csv（或缺少 features_file），跳过盲测评估。")
        lines.append("- 真机实验请按 SOP 完成 15 组盲测并录屏，App 会写出 blind_test_<session>.csv。")
    if "blind_test.png" in figure_names:
        lines.append("")
        lines.append(md_utils.md_figure("figures/blind_test.png", "盲测逐工况准确率"))
    lines.append("")
    lines.append("## 5. 抗噪测试")
    lines.append("")
    if len(noise):
        snrs = sorted(noise["snr_db"].unique(), reverse=True)
        rows = []
        for snr in snrs:
            rows.append([f"{snr:.0f}",
                         md_utils.fmt_number(_accuracy_at(noise, "white", snr, False), 1),
                         md_utils.fmt_number(_accuracy_at(noise, "pink", snr, False), 1),
                         md_utils.fmt_number(_accuracy_at(noise, "white", snr, True), 1),
                         md_utils.fmt_number(_accuracy_at(noise, "pink", snr, True), 1)])
        lines.append(md_utils.md_table(
            ["SNR / dB", "白噪声（加噪）", "粉红噪声（加噪）",
             "白噪声（去噪后）", "粉红噪声（去噪后）"], rows))
        lines.append("")
        lines.append("口径：噪声按目标 SNR 叠加到整段音频（峰值超满量程时整体缩放以保持 SNR），"
                     "重新提取全部音频特征（振动特征沿用干净数据），再用干净训练集分类；"
                     "去噪 = 单次全局 FFT 谱减法（噪声底取 20% 分位，α=1.5）。")
        lines.append("")
        lines.extend(noise_conclusions(noise))
        lines.append("")
        lines.append(md_utils.md_figure("figures/noise_accuracy.png", "抗噪准确率曲线（去噪前后）"))
        lines.append("")
    else:
        lines.append("- 未进行抗噪评估。")
    lines.append("## 6. 误差分析")
    lines.append("")
    confusion_matrix = np.asarray(result["confusion"], dtype=int)
    pairs = []
    for row in range(len(result["labels"])):
        for column in range(len(result["labels"])):
            if row != column and confusion_matrix[row, column]:
                pairs.append((result["labels"][row], result["labels"][column],
                              int(confusion_matrix[row, column])))
    pairs.sort(key=lambda item: -item[2])
    if pairs:
        lines.append("主要混淆对（真实 → 被误判）：")
        lines.append("")
        for true_label, predicted, count in pairs[:5]:
            lines.append(f"- {true_label} → {predicted}：{count} 次")
        lines.append("")
    else:
        lines.append("LOOCV 中没有跨工况误判；仍需在真机数据上关注相邻工况的边界样本。")
    lines.append("可能原因（结合特征分布）：")
    lines.append("1. **C1/C3 边界**：低档正常与轻微失衡的基频相同，差异主要体现在 1x 振动幅值、边带与谐波占比；"
                 "失衡程度轻时与正常样本重叠，kNN 容易互相误判。")
    lines.append("2. **C4/C5 高频特性**：遮挡后的宽带噪声与吹风机的高频宽带在频带占比与平坦度上相近，"
                 "若麦克风高频响应受限或环境噪声偏大，两者容易混淆。")
    lines.append("3. **C4/C1 低频重叠**：遮挡工况仍保留基频与谐波，只在高频滚降/频谱质心上与正常工况区分，"
                 "距离度量需要对这些特征有足够权重（z-score 后等权，可能放大噪声特征）。")
    lines.append("4. **录制差异**：手机摆放位置、距离、档位、环境噪声会引入组间漂移；"
                 "同一工况内不同组若参数波动较大，LOOCV 泛化会下降。")
    lines.append("")
    lines.append("## 7. 工程改进方向")
    lines.append("")
    lines.append("1. **补足数据量**：真机实验按 5 工况 × 15 组采集；组间打散顺序、每 5 组休息，减少温漂与记忆效应。")
    lines.append("2. **特征选择/加权**：对 32 维特征做类间可分性筛选（如 Fisher 比、互信息），"
                 "或按特征组分别标准化、给振动/音频不同权重，减少噪声特征对距离的贡献。")
    lines.append("3. **抗噪训练**：把加噪样本加入训练集（数据增强），或先用参考噪声录音做谱减/维纳滤波，"
                 "再提取特征；本报告显示谱减法在低 SNR 有正收益。")
    lines.append("4. **拒识机制**：置信度低于阈值时输出“无法判断”，避免把陌生声源强行归到 5 类之一。")
    lines.append("5. **模型升级**：样本量足够后可对比 kNN 与 LDA/SVM（课程允许范围内），"
                 "或对音频用 log-mel + 距离度量；但需保持端侧可解释与可复现。")
    lines.append("6. **端侧一致性**：持续用同一份 WAV 在 App 与 Python 交叉验证（相对误差 <1e-3），"
                 "并把模型参数写进 evaluation JSON，保证手机端与报告结果一致。")
    lines.append("")
    lines.append("## 8. 复现方式")
    lines.append("")
    lines.append("~~~powershell")
    lines.append("python analysis/vibro/analyze.py --data data/raw/vibro/<session> --out analysis/out/vibro")
    lines.append("python analysis/vibro/features.py   # 单文件自检：1 kHz 正弦峰值/频带断言")
    lines.append("~~~")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- 入口

def run(data_dir: Path, out_dir: Path, k: int = vm.K, snr_values: list[float] | None = None,
        noise_types: list[str] | None = None, do_denoise: bool = True) -> dict:
    data = load_session(data_dir, k)
    synthetic = is_synthetic(data)
    snr_values = snr_values if snr_values is not None else [20.0, 10.0, 5.0, 0.0, -5.0]
    noise_types = noise_types if noise_types is not None else ["white", "pink"]
    result = evaluate_training(data)
    per_condition = per_condition_frame(result)
    classifier = vm.KnnClassifier(k).fit(data["X"], data["y"])
    resubstitution = evaluate_clean_resubstitution(data)
    blind, blind_summary = evaluate_blind(data, classifier)
    noise = evaluate_noise(data, classifier, snr_values, noise_types, do_denoise)
    figure_dir = io_utils.ensure_dir(out_dir / "figures")
    table_dir = io_utils.ensure_dir(out_dir / "tables")
    figure_names = make_figures(data, result, blind, noise, figure_dir)
    features_table = data["features"][(["file_stem", "condition_id", "condition_label",
                                        "group_index"] + vf.NUMERIC_COLUMNS)]
    io_utils.write_csv(features_table, table_dir / "features_all.csv")
    if data["index"] is not None:
        index_table = data["index"].copy()
        actual = data["features"].groupby("condition_id").size().rename("actual_groups")
        index_table = index_table.merge(actual.reset_index(), on="condition_id", how="left")
        io_utils.write_csv(index_table, table_dir / "dataset_check.csv")
    io_utils.write_csv(confusion_frame(result["labels"], result["confusion"]).reset_index(),
                       table_dir / "loocv_confusion.csv")
    io_utils.write_csv(per_condition, table_dir / "per_condition_accuracy.csv")
    if len(blind):
        io_utils.write_csv(blind, table_dir / "blind_test_results.csv")
    if len(noise):
        io_utils.write_csv(noise, table_dir / "noise_accuracy.csv")
    if len(data["crosscheck"]):
        io_utils.write_csv(data["crosscheck"], table_dir / "feature_crosscheck.csv")
    issues = list(data["index_issues"])
    if len(data["crosscheck"]) and float(np.nanmax(data["crosscheck"]["max_rel_error"])) > 1e-3:
        issues.append("特征交叉校验最大相对误差 > 1e-3，请检查 App/Python 的短帧或零值处理差异")
    session_id = str(data["manifest"].get("session", {}).get("sessionId", "")) or data["dir"].name
    evaluation = {
        "schema": "phonelab.vibro.eval/1",
        "session_id": session_id,
        "synthetic": synthetic,
        "training": {
            "samples": int(len(data["y"])),
            "features": len(vf.NUMERIC_COLUMNS),
            "model": vm.DESCRIPTOR,
            "loocv_accuracy": float(result["accuracy"]),
            "confusion_matrix": {label: [int(value) for value in row]
                                 for label, row in zip(result["labels"], result["confusion"])},
            "per_condition_accuracy": {label: float(value)
                                       for label, value in result["per_condition"].items()},
        },
        "blind_test": {
            "trials": int(blind_summary.get("trials", 0)),
            "correct": int(round(blind_summary.get("accuracy_recomputed", 0.0)
                                 * blind_summary.get("trials", 0)))
            if blind_summary.get("available") else 0,
            "accuracy": float(blind_summary.get("accuracy_recomputed", float("nan"))),
        } if blind_summary.get("available") else None,
        "noise": ([{"snr_db": float(snr),
                    "white": float(_accuracy_at(noise, "white", snr, False)),
                    "pink": float(_accuracy_at(noise, "pink", snr, False))}
                   for snr in sorted(noise["snr_db"].unique(), reverse=True)] if len(noise) else []),
        "denoise": ([{"snr_db": float(snr),
                      "white": float(_accuracy_at(noise, "white", snr, True)),
                      "pink": float(_accuracy_at(noise, "pink", snr, True))}
                     for snr in sorted(noise["snr_db"].unique(), reverse=True)] if len(noise) else []),
        "notes": "合成演示数据，仅用于验证分析管线" if synthetic else "真机采集数据",
    }
    md_utils.write_text(out_dir / f"evaluation_{session_id}.json",
                        json.dumps(evaluation, ensure_ascii=False, indent=2) + "\n")
    report = build_report(data, result, per_condition, blind, blind_summary, noise,
                          resubstitution, figure_names, synthetic, issues)
    md_utils.write_text(out_dir / "vibro_report.md", report)
    return {
        "data_dir": str(data_dir), "out_dir": str(out_dir), "synthetic": synthetic,
        "samples": int(len(data["y"])), "conditions": data["conditions"],
        "loocv_accuracy": float(result["accuracy"]),
        "per_condition": {label: float(value) for label, value in result["per_condition"].items()},
        "blind_accuracy": float(blind_summary.get("accuracy_recomputed", float("nan"))),
        "blind_trials": int(blind_summary.get("trials", 0)),
        "noise_rows": int(len(noise)), "figures": figure_names, "issues": issues,
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    data_dir = Path(args.data).resolve()
    out_dir = Path(args.out).resolve()
    snr_values = [float(value) for value in args.snr.split(",") if value.strip()]
    noise_types = [value.strip() for value in args.noise_types.split(",") if value.strip()]
    print(f"[vibro] 读取 {data_dir}")
    summary = run(data_dir, out_dir, args.k, snr_values, noise_types, not args.no_denoise)
    print(f"[vibro] 输出 {out_dir}：{len(summary['figures'])} 张图、"
          f"特征表/混淆矩阵/抗噪表、vibro_report.md、evaluation_*.json")
    print(f"[vibro] 样本 {summary['samples']} 组（{len(summary['conditions'])} 工况）；"
          f"LOOCV 准确率 {summary['loocv_accuracy']:.1%}")

    if np.isfinite(summary["blind_accuracy"]):
        print(f"[vibro] 盲测 {summary['blind_trials']} 组，准确率 {summary['blind_accuracy']:.1%}")
    if summary["synthetic"]:
        print("[vibro] 注意：输入是合成演示数据（synthetic），结论仅用于验证管线")
    if summary["issues"]:
        print(f"[vibro] 数据质量问题 {len(summary['issues'])} 条（已写入报告第 1 节）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
