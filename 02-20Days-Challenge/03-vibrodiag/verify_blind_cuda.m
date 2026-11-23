function verify_blind_cuda(outDir)
% Inference only: compare phone-exported features and independent re-extraction.
base = fileparts(mfilename('fullpath'));
modelFile = fullfile(base, 'results', 'vibro_cuda_model.mat');
reference = readtable(fullfile(outDir, 'phone_predictions.csv'), 'TextType', 'string');
[~, fromPhone, s1] = predict_vibro_cuda(modelFile, fullfile(outDir, 'phone_features.csv'));
[~, fromRaw, s2] = predict_vibro_cuda(modelFile, fullfile(outDir, 'recomputed_features.csv'));
loaded = load(modelFile, 'model');
phoneScores = reference{:, cellstr("phone_" + loaded.model.conditions)};
delta = max(abs(s1 - phoneScores), [], 2);
rawDelta = max(abs(s1 - s2), [], 2);
verified = [reference, table(fromPhone, fromRaw, delta, rawDelta, ...
    'VariableNames', {'matlab_from_phone', 'matlab_from_raw', 'max_phone_score_delta', 'max_reextract_score_delta'})];
writetable(verified, fullfile(outDir, 'predictions_verified.csv'));
labels = loaded.model.conditions;
[~, y] = ismember(reference.true_label, labels);
[~, z] = ismember(fromRaw, labels);
cm = array2table(accumarray([y z], 1, [5 5]), 'VariableNames', cellstr(labels));
cm = addvars(cm, labels, 'Before', 1, 'NewVariableNames', 'true_condition');
writetable(cm, fullfile(outDir, 'confusion_verified.csv'));
device = gpuDevice();
fprintf('GPU: %s\n', device.Name);
fprintf('Phone vs MATLAB, saved features: %d/%d label agreement | max score delta %.9g\n', nnz(fromPhone == reference.phone_label), height(reference), max(delta));
fprintf('MATLAB, re-extracted raw vs saved features: %d/%d label agreement | max score delta %.9g\n', nnz(fromRaw == fromPhone), height(reference), max(rawDelta));
fprintf('Blind test accuracy, raw re-extraction: %d/%d\n', nnz(fromRaw == reference.true_label), height(reference));
end
