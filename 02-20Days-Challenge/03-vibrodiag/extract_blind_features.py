"""Extract the same feature schema for a new, unlabeled WAV/accelerometer pair."""
from __future__ import annotations

import argparse
import csv
import hashlib
import math
import wave
from pathlib import Path

from features import NUMERIC_COLUMNS, extract_from_files


def pcm_sha256(path: Path) -> str:
    # Compare PCM bytes, not WAV headers, so repackaging does not bypass the guard.
    with wave.open(str(path), 'rb') as fd:
        return hashlib.sha256(fd.readframes(fd.getnframes())).hexdigest()


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--trial-id', required=True)
    p.add_argument('--wav', type=Path, required=True)
    p.add_argument('--accel', type=Path, required=True)
    p.add_argument('--training-root', type=Path, required=True, help='Original 75-group directory; used for duplicate guard')
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    training = sorted(a.training_root.glob('C*/g*/*_audio.wav'))
    if len(training) != 75:
        p.error(f'Expected 75 training WAVs, found {len(training)}')
    raw_id = pcm_sha256(a.wav)
    if any(pcm_sha256(path) == raw_id for path in training):
        p.error('Blind WAV is an exact copy of a training WAV. Record a new trial.')
    with wave.open(str(a.wav), 'rb') as f:
        if (f.getnchannels(), f.getsampwidth(), f.getframerate()) != (1, 2, 44100) or f.getnframes() != 661500:
            p.error('Match the current training protocol: mono PCM16 44.1 kHz WAV with 15 s of audio.')
    if not a.accel.is_file() or a.accel.stat().st_size == 0:
        p.error('A matching accelerometer CSV is required.')
    values = extract_from_files(a.wav, a.accel)
    if not math.isfinite(values.get('audio_rms_dbfs', float('nan'))) or not math.isfinite(values.get('accel_rms', float('nan'))):
        p.error('Audio or acceleration data failed feature extraction.')
    with a.out.open('x', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['trial_id', *NUMERIC_COLUMNS])
        w.writerow([a.trial_id, *[format(v, '.12g') if math.isfinite(v := values.get(k, float('nan'))) else '' for k in NUMERIC_COLUMNS]])
    print(f'Wrote {a.out} (no condition label in classifier input; keep the true label separate)')


if __name__ == '__main__':
    main()
