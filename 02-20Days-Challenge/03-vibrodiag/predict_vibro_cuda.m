function [classIndex, classId, score] = predict_vibro_cuda(model, data)
% Use exactly the training feature schema and train-only imputation/scaling.
% Input: frozen model struct or .mat path, and raw numeric features or a CSV path.
% Score is an RBF-ridge ranking score, NOT a calibrated probability.
if ischar(model) || isstring(model)
    loaded = load(model, 'model'); model = loaded.model;
end
if ischar(data) || isstring(data)
    tbl = readtable(data, 'TextType', 'string');
    assert(all(ismember(model.featureNames, tbl.Properties.VariableNames)), 'CSV missing required feature columns.');
    data = nan(height(tbl),numel(model.featureNames));
    for j = 1:numel(model.featureNames)
        col = tbl.(model.featureNames{j});
        if isnumeric(col), data(:,j) = double(col);
        else, data(:,j) = str2double(string(col)); end
    end
end
assert(size(data,2) == numel(model.featureNames), 'Wrong number of features.');
X = data(:,model.keep);
assert(all(any(isfinite(X),2)), 'A sample has no usable features.');
for j = 1:size(X,2), X(~isfinite(X(:,j)),j) = model.fill(j); end
Z = gpuArray(single((X-model.mu)./model.sigma));
R = gpuArray(model.trainZ);
D = sum(Z.^2,2) + sum(R.^2,2)' - 2 * (Z*R');
score = gather(exp(-single(model.gamma) * max(D,0)) * gpuArray(model.alpha));
[~,classIndex] = max(score,[],2);
classId = model.conditions(classIndex);
end
