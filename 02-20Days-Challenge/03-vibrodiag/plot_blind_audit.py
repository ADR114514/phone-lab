"""Compare the recorded blind signals with training ranges, and plot verified confusion."""
from pathlib import Path
import csv
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

HERE = Path(__file__).resolve().parent
OUT = HERE / '盲测复核'
CLASSES = ('C1_NORMAL_LOW', 'C2_NORMAL_HIGH', 'C3_IMBALANCE', 'C4_BLOCKED', 'C5_HAIRDRYER')
SHORT = ('C1 Low', 'C2 High', 'C3 Imbalance', 'C4 Blocked', 'C5 Dryer')
COLORS = ('#33629f', '#d07828', '#6c4999', '#45835a', '#b4395f')


def read(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f))


def main():
    training = read(HERE / 'inputs' / 'clean.csv')
    blind = read(OUT / 'signal_stats.csv')
    cm = np.asarray([[int(r[k]) for k in CLASSES] for r in read(OUT / 'confusion_verified.csv')])
    fig, (signals, matrix) = plt.subplots(1, 2, figsize=(12.2, 5.1), gridspec_kw={'width_ratios':[1.3,1]}, layout='constrained')
    for key, color in zip(CLASSES, COLORS):
        group = [r for r in training if r['condition_id'] == key]
        signals.scatter([float(r['audio_rms_dbfs']) for r in group], [float(r['audio_peak_hz']) for r in group],
                        s=33,color=color,alpha=.56, edgecolors='none')
        group = [r for r in blind if r['true_label'] == key]
        signals.scatter([float(r['audio_rms_dbfs']) for r in group], [float(r['audio_peak_hz']) for r in group],
                        s=70,color=color,marker='x',linewidths=1.9)
    for key,color in zip(SHORT,COLORS):
        signals.scatter([],[],s=29,color=color,label=key)
    signals.legend(fontsize=8, loc='lower left', ncols=2)
    signals.set(yscale='log', xlabel='Recorded audio RMS (dBFS)', ylabel='Dominant FFT frequency (Hz)',
                title='Recorded signals: training dots vs blind crosses')
    signals.grid(alpha=.20, which='both')
    matrix.imshow(cm, cmap='Blues', vmin=0, vmax=3)
    matrix.set(xticks=range(5), yticks=range(5), xticklabels=SHORT, yticklabels=SHORT,
               xlabel='Predicted condition', ylabel='Recorded ground-truth label', title='Independent blind test: 2 / 15 (13.3%)')
    plt.setp(matrix.get_xticklabels(), rotation=36, ha='right')
    for i in range(5):
        for j in range(5):
            matrix.text(j,i,str(cm[i,j]),ha='center',va='center',color='white' if cm[i,j]>=2 else '#182637')
    fig.savefig(OUT / 'blind_signal_and_confusion.png', dpi=165)


if __name__ == '__main__':
    main()
