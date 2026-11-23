"""Plot the genuine C2 g02 / g03 recordings, with one-second time-domain RMS and FFT."""
import argparse
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

from features import AUDIO_FFT_SIZE, AUDIO_HOP, average_power_spectrum, hann_window, read_wav


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('source', type=Path)
    cli.add_argument('output', type=Path)
    args = cli.parse_args()
    fig, axes = plt.subplots(2, 1, figsize=(8, 6), layout='constrained')
    for group, color in [(2, '#3568a4'), (3, '#c75420')]:
        code = f'g{group:02d}'
        path = args.source / 'C2_NORMAL_HIGH' / code / f'{code}_C2_NORMAL_HIGH_audio.wav'
        pcm, fs = read_wav(path)
        blocks = pcm[:len(pcm)//fs*fs].reshape(-1,fs)
        rms = 20*np.log10(np.maximum(np.sqrt(np.mean(blocks**2, axis=1)), 1e-12))
        axes[0].plot(np.arange(len(rms))+0.5, rms, 'o-', ms=3, color=color, label=f'C2 {code}')
        power = average_power_spectrum(pcm, AUDIO_FFT_SIZE, AUDIO_HOP, hann_window(AUDIO_FFT_SIZE))
        hz = np.fft.rfftfreq(AUDIO_FFT_SIZE, 1/fs)
        axes[1].semilogx(hz[1:], 10*np.log10(np.maximum(power[1:], 1e-16)), color=color, lw=1, label=f'C2 {code}')
    axes[0].set(xlabel='Time (s)', ylabel='1 s RMS (dBFS)', title='High-speed fan recordings: time-domain comparison')
    axes[1].set(xlim=(20,20000), xlabel='Frequency (Hz)', ylabel='Mean FFT power (dB, common scale)')
    for ax in axes:
        ax.grid(alpha=.22)
        ax.legend()
    fig.savefig(args.output, dpi=160)


if __name__ == '__main__':
    main()
